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
            [clojure.set :as set]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.render :as render]
            [slopp.index :as index]
            [slopp.repl :as repl]
            [slopp.image :as image]
            [slopp.edit :as edit]
            [slopp.refactor :as refactor]
            [slopp.db :as db]))

(defn- start-spare!
  "Kick off a background-warming spare image (D5 warm spare) if enabled."
  [session]
  (when (:warm-spare? @session)
    (swap! session assoc :spare (future (repl/start!)))))

(defn open!
  "Start a session: the owned image + the store — loaded from `<dir>/.slopp/`
  when `:dir` is given and it has history, empty otherwise. `:warm-spare? true`
  keeps a spare image warming in the background so restarts are near-instant."
  ([] (open! {}))
  ([{:keys [dir warm-spare?]}]
   (let [conn    (when dir (db/open! dir))
         store   (or (some-> conn db/load-store) (store/empty-store))
         image   (repl/start!)
         session (atom {:store store :image image :db conn
                        :warm-spare? (boolean warm-spare?)})]
     (start-spare! session)
     (doseq [ns-sym (keys (:namespaces store))]
       (image/load-ns! image store ns-sym))
     session)))

(defn close! [session]
  (repl/stop! (:image @session))
  (when-let [spare (:spare @session)]
    (repl/stop! @spare))                          ; reap even if still booting
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
                    (some #{id} (:form-ids d))))
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
  construction (the D5 backstop). With a warm spare, the swap avoids a JVM boot
  on the critical path; the next spare starts warming immediately."
  [session]
  (let [{:keys [image spare]} @session
        fresh (if spare @spare (repl/start!))]    ; deref: ready or nearly so
    (repl/stop! image)
    (swap! session assoc :image fresh :spare nil)
    (start-spare! session)
    (let [{:keys [store image]} @session]
      (doseq [ns-sym (keys (:namespaces store))]
        (image/load-ns! image store ns-sym)))))

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
              (merge-with (fn [a b]
                            (cond (number? a) (+ a b)
                                  (and (sequential? a) (sequential? b)) (into (vec a) b)
                                  :else (or b a)))
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

(defn add-form!
  "Add a new top-level form to `ns-sym` (O1 base write): dialect gate, `:add`
  delta, hot-reload into the image, verification, provenance. Returns
  {:delta :warnings :test :affected} or {:error msg}."
  [session ns-sym source & {:keys [prompt]}]
  (let [{:keys [node error]} (edit/parse-form source)
        nm (some-> node store/form-symbol)]
    (cond
      error {:error error}

      (and nm (store/form-named (:store @session) ns-sym nm))
      {:error (str nm " already exists in " ns-sym)}

      :else
      (if-let [[st' delta] (store/append-form (:store @session) ns-sym node
                                              :prompt prompt)]
        (do (swap! session assoc :store st')
            (persist-last! session)
            (let [image (:image @session)]
              (repl/eval! image (format "(in-ns '%s)" ns-sym))
              (repl/eval! image source))
            (let [affected (when nm (affected-tests session ns-sym nm))
                  summary  (run-verification! session ns-sym affected)]
              (swap! session update :store store/record-verification ns-sym summary)
              (persist-last! session)
              {:delta    delta
               :warnings (edit/ns-warnings (:store @session) ns-sym)
               :test     summary
               :affected (or affected :all)}))
        {:error (str "no namespace " ns-sym " (ingest it first)")}))))

(defn delete-form!
  "Delete the form named `nm` from `ns-sym`: `:delete` delta, `ns-unmap` in the
  image, verification (tests that exercised it will go red — the honest signal
  if it was still referenced), provenance."
  [session ns-sym nm & {:keys [prompt]}]
  (if-let [[st' delta] (store/remove-form (:store @session) ns-sym nm
                                          :prompt prompt)]
    (do (swap! session assoc :store st')
        (persist-last! session)
        (let [affected (affected-tests session ns-sym nm)]
          (repl/eval! (:image @session) (format "(ns-unmap '%s '%s)" ns-sym nm))
          (let [summary (run-verification! session ns-sym affected)]
            (swap! session update :store store/record-verification ns-sym summary)
            (persist-last! session)
            {:delta delta :test summary :affected (or affected :all)})))
    {:error (str "no form named " nm " in " ns-sym)}))

(defn test-run!
  "Traced, diagnosed run of `ns-sym`'s tests; refreshes the test→form map and
  records the result (C4)."
  [session ns-sym]
  (let [summary (diagnosed-run! session ns-sym nil)]
    (swap! session update :store store/record-verification ns-sym summary)
    (persist-last! session)
    summary))

(defn- rename-in-trace
  "Carry the observed test→form map across a rename (old qsym → new qsym)."
  [tmap qold qnew]
  (into {}
        (map (fn [[t forms]]
               [(if (= t qold) qnew t)
                (into #{} (map #(if (= % qold) qnew %)) forms)]))
        tmap))

(defn rename!
  "Rename `ns-sym/old-name` to `new-name` everywhere: ONE coordinated delta over
  the def + every reference across all namespaces (position-based via the index,
  so shadowed locals are untouched — see slopp.refactor). Hot-reloads every
  rewritten form, drops the old var (`ns-unmap`), re-verifies the affected
  tests, and records the outcome. Returns {:delta :renamed :test :affected} or
  {:error msg}."
  [session ns-sym old-name new-name & {:keys [prompt]}]
  (let [st   (:store @session)
        qold (symbol (str ns-sym) (str old-name))
        qnew (symbol (str ns-sym) (str new-name))]
    (cond
      (nil? (store/form-named st ns-sym old-name))
      {:error (str "no form named " old-name " in " ns-sym)}

      (store/form-named st ns-sym new-name)
      {:error (str new-name " already exists in " ns-sym)}

      :else
      (let [changeset    (refactor/rename-changeset st ns-sym old-name new-name)
            [st' delta]  (store/apply-changeset st :rename ns-sym changeset
                                                :prompt prompt
                                                :extra {:old old-name :new new-name})
            touched-nses (distinct (map #(store/ns-of-form-id st' %) (keys changeset)))
            ;; affected tests, judged against the PRE-rename trace map
            changed-syms (into #{qold qnew}
                               (keep (fn [id]
                                       (let [e (store/form-by-id st' id)]
                                         (when (:name e)
                                           (symbol (str (store/ns-of-form-id st' id))
                                                   (str (:name e)))))))
                               (keys changeset))
            tmap         (:test-map @session)
            affected     (when (seq tmap)
                           (let [hits (->> tmap
                                           (keep (fn [[t forms]]
                                                   (when (or (contains? changed-syms t)
                                                             (seq (set/intersection forms changed-syms)))
                                                     (if (= t qold) qnew t))))
                                           sort vec)]
                             (when (seq hits) hits)))]
        (swap! session assoc :store st')
        (swap! session update :test-map rename-in-trace qold qnew)
        (when-let [db (:db @session)]
          (db/persist! db st' delta touched-nses))
        ;; hot-reload every rewritten form in its namespace; drop the old var
        (let [image (:image @session)]
          (doseq [id (keys changeset)
                  :let [e   (store/form-by-id st' id)
                        ens (store/ns-of-form-id st' id)]]
            (repl/eval! image (format "(in-ns '%s)" ens))
            (repl/eval! image (n/string (:node e))))
          (repl/eval! image (format "(ns-unmap '%s '%s)" ns-sym old-name)))
        (let [summary (run-verification! session ns-sym affected)]
          (swap! session update :store store/record-verification ns-sym summary)
          (persist-last! session)
          {:delta    delta
           :renamed  {:old qold :new qnew :forms (count changeset)}
           :test     summary
           :affected (or affected :all)})))))

(defn build!
  "C1/C6 explicit build: materialize every namespace's current source to real
  `.clj` files under `dir`. Returns `dir`."
  [session dir]
  (doseq [ns-sym (keys (:namespaces (:store @session)))]
    (let [file (io/file dir (str (str/replace (str ns-sym) "." "/") ".clj"))]
      (io/make-parents file)
      (spit file (render/render-ns (:store @session) ns-sym))))
  dir)
