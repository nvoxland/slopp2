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

;; --- edit.* / runtime ---

(defn edit-replace!
  "Replace the form `nm` in `ns-sym` with `new-source` (O1 whole-form replace),
  running the full pipeline and updating the session on success."
  [session ns-sym nm new-source & {:keys [prompt]}]
  (let [r (edit/apply-replace! @session ns-sym nm new-source :prompt prompt)]
    (when-not (:error r)
      (reset! session (:system r))
      ;; two deltas landed (:replace, then :verify from the test re-run)
      (let [{:keys [db store]} @session]
        (when db
          (doseq [d (take-last 2 (store/deltas store))]
            (db/persist! db store d)))))
    r))

(defn test-run!
  "Run `ns-sym`'s tests in the image, recording the result (C4)."
  [session ns-sym]
  (let [res (image/test-run (:image @session) ns-sym)]
    (swap! session update :store store/record-verification ns-sym res)
    (persist-last! session)
    res))

(defn restart!
  "D5 backstop: throw the image away, start a fresh one, and reload every
  namespace from the store — a faithful image by construction."
  [session]
  (swap! session update :image repl/restart!)
  (let [{:keys [store image]} @session]
    (doseq [ns-sym (keys (:namespaces store))]
      (image/load-ns! image store ns-sym)))
  session)

(defn build!
  "C1/C6 explicit build: materialize every namespace's current source to real
  `.clj` files under `dir`. Returns `dir`."
  [session dir]
  (doseq [ns-sym (keys (:namespaces (:store @session)))]
    (let [file (io/file dir (str (str/replace (str ns-sym) "." "/") ".clj"))]
      (io/make-parents file)
      (spit file (render/render-ns (:store @session) ns-sym))))
  dir)
