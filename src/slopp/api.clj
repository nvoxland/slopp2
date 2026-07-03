(ns slopp.api
  "The agent-facing operation surface (the tools an MCP adapter exposes). A
  session is an atom holding the evolving store + the owned image. Everything is
  form-addressed (ns/name), never file+line: the agent *sees* code via
  `query-source` (the VFS) and *edits* only through `edit-replace!` (tracked
  deltas). `query-eval` lets it observe the live image (the oracle) without
  mutating code.

  With `{:dir ...}` the session is durable (C7): the store is write-through to
  SQLite at `<dir>/.slopp/store.db`, and `open!` reconstructs both the store and
  the live image from it. Without `:dir` the session is ephemeral (tests,
  scratch)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.render :as render]
            [slopp.index :as index]
            [slopp.repl :as repl]
            [slopp.image :as image]
            [slopp.edit :as edit]
            [slopp.db :as db]))

(defn open!
  "Start a session: the owned image + the store — loaded from `<dir>/.slopp/`
  when `:dir` is given and it has history, empty otherwise."
  ([] (open! {}))
  ([{:keys [dir]}]
   (let [conn    (when dir (db/open! dir))
         store   (or (some-> conn db/load-store) (store/empty-store))
         image   (repl/start!)
         session (atom {:store store :image image :db conn})]
     (doseq [ns-sym (keys (:namespaces store))]
       (image/load-ns! image store ns-sym))
     session)))

(defn close! [session]
  (repl/stop! (:image @session))
  (when-let [^java.sql.Connection conn (:db @session)]
    (.close conn))
  nil)

(defn- persist-last!
  "Write-through (C7): land the store's newest delta (plus its namespace's
  current elements) in the db, atomically. No-op for ephemeral sessions."
  [session]
  (let [{:keys [db store]} @session]
    (when db
      (db/persist! db store (last (store/deltas store))))))

(defn ingest!
  "Ingest `source` as `ns-sym` and load it into the live image."
  [session ns-sym source]
  (swap! session update :store store/ingest ns-sym source)
  (persist-last! session)
  (image/load-ns! (:image @session) (:store @session) ns-sym)
  session)

;; --- query.* (read) ---

(defn query-source
  "Render `ns-sym`'s current source from the store (the VFS read)."
  [session ns-sym]
  (render/render-ns (:store @session) ns-sym))

(defn query-symbol
  "Describe the form defining `nm`: id, name, effectfulness (D6), source."
  [session ns-sym nm]
  (let [st  (:store @session)
        f   (store/form-named st ns-sym nm)
        eff (index/effectful-vars (index/analyze (render/render-ns st ns-sym)))]
    (when f
      {:id         (:id f)
       :name       (:name f)
       :effectful? (contains? eff (symbol (str ns-sym) (str nm)))
       :source     (n/string (:node f))})))

(defn query-references
  "Usages of `ns-sym/nm` — who references it."
  [session ns-sym nm]
  (index/references (index/analyze (render/render-ns (:store @session) ns-sym))
                    ns-sym nm))

(defn query-lineage
  "Provenance chain for `nm`: the deltas that created or changed its form (who
  touched it, via which op, driven by which prompt)."
  [session ns-sym nm]
  (let [st (:store @session)
        id (:id (store/form-named st ns-sym nm))]
    (when id
      (filter (fn [d]
                (or (= id (:form-id d))
                    (and (= :ingest (:op d)) (some #{id} (:form-ids d)))))
              (store/deltas st)))))

(defn query-eval
  "Read-only eval against the live image (the oracle). Does NOT change code."
  [session code]
  (repl/eval! (:image @session) code))

;; --- verification (D1 tracing + D5 restart-as-diagnostic) ---

(defn- green? [summary]
  (zero? (+ (:fail summary 0) (:error summary 0))))

(defn- fresh-image!
  "Replace the image with a fresh process reloaded from the store — faithful by
  construction (the D5 backstop)."
  [session]
  (swap! session update :image repl/restart!)
  (let [{:keys [store image]} @session]
    (doseq [ns-sym (keys (:namespaces store))]
      (image/load-ns! image store ns-sym))))

(defn restart!
  "D5 escape hatch: the agent-callable fresh-image restart."
  [session]
  (fresh-image! session)
  session)

(defn- traced-run!
  "Run `test-ns`'s tests (all, or `only` names) with form-tracing; absorb the
  observed test→form map into the session; return the summary."
  [session test-ns only]
  (let [{:keys [image store]} @session
        {:keys [summary trace]} (image/traced-test-run image store test-ns :only only)]
    (swap! session update :test-map merge trace)
    summary))

(defn- diagnosed-run!
  "Run tests; on red, cross-check on a fresh image before believing it (D5
  restart-as-diagnostic — the oracle must not return a false verdict).
  red→green ⇒ the red was image staleness: healed, flagged.
  red→red   ⇒ a real failure, confirmed against a faithful image."
  [session test-ns only]
  (let [r1 (traced-run! session test-ns only)]
    (if (green? r1)
      r1
      (do (fresh-image! session)
          (let [r2 (traced-run! session test-ns only)]
            (if (green? r2)
              (assoc r2 :staleness-detected true)
              (assoc r2 :fresh-confirmed true)))))))

(defn- affected-tests
  "Which tests must re-run after editing `ns-sym/nm`: the tests observed (via
  tracing) to exercise that form — or the form itself if it IS a test. nil =
  no trace information; run everything (conservative)."
  [session ns-sym nm]
  (let [qform (symbol (str ns-sym) (str nm))
        tmap  (:test-map @session)]
    (if (contains? tmap qform)
      [qform]
      (let [hits (->> tmap
                      (keep (fn [[t forms]] (when (contains? forms qform) t)))
                      sort vec)]
        (when (seq hits) hits)))))

(defn- run-verification!
  "Diagnosed run of `affected` tests (grouped by their namespace), or of all of
  `default-ns`'s tests when there's no trace information."
  [session default-ns affected]
  (if (nil? affected)
    (diagnosed-run! session default-ns nil)
    (reduce (fn [acc [tns tsyms]]
              (merge-with (fn [a b] (if (number? a) (+ a b) (or b a)))
                          acc
                          (diagnosed-run! session tns (mapv (comp symbol name) tsyms))))
            {}
            (group-by (comp symbol namespace) affected))))

;; --- edit.* / runtime ---

(defn edit-replace!
  "Replace the form `nm` in `ns-sym` with `new-source` (O1 whole-form replace):
  pipeline + hot-reload, then re-verify — only the tests the trace map says
  exercise this form (D1), cross-checked on a fresh image if red (D5) — and
  record the outcome as provenance (C4)."
  [session ns-sym nm new-source & {:keys [prompt]}]
  (let [r (edit/apply-replace! @session ns-sym nm new-source :prompt prompt)]
    (if (:error r)
      r
      (let [_        (reset! session (:system r))
            _        (persist-last! session)          ; the :replace delta
            affected (affected-tests session ns-sym nm)
            summary  (run-verification! session ns-sym affected)]
        (swap! session update :store store/record-verification ns-sym summary)
        (persist-last! session)                       ; the :verify delta
        {:delta    (:delta r)
         :warnings (:warnings r)
         :test     summary
         :affected (or affected :all)}))))

(defn test-run!
  "Traced, diagnosed run of `ns-sym`'s tests; refreshes the test→form map and
  records the result (C4)."
  [session ns-sym]
  (let [summary (diagnosed-run! session ns-sym nil)]
    (swap! session update :store store/record-verification ns-sym summary)
    (persist-last! session)
    summary))

(defn build!
  "C1/C6 explicit build: materialize every namespace's current source to real
  `.clj` files under `dir`. Returns `dir`."
  [session dir]
  (doseq [ns-sym (keys (:namespaces (:store @session)))]
    (let [file (io/file dir (str (str/replace (str ns-sym) "." "/") ".clj"))]
      (io/make-parents file)
      (spit file (render/render-ns (:store @session) ns-sym))))
  dir)
