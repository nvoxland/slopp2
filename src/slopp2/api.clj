(ns slopp2.api
  "The agent-facing operation surface (the tools an MCP adapter exposes). A
  session is an atom holding the evolving store + the owned image. Everything is
  form-addressed (ns/name), never file+line: the agent *sees* code via
  `query-source` (the VFS) and *edits* only through `edit-replace!` (tracked
  deltas). `query-eval` lets it observe the live image (the oracle) without
  mutating code.

  The MCP stdio transport is a thin deferred adapter over these functions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp2.store :as store]
            [slopp2.render :as render]
            [slopp2.index :as index]
            [slopp2.repl :as repl]
            [slopp2.image :as image]
            [slopp2.edit :as edit]))

(defn open!
  "Start a session: a fresh owned image + an empty store."
  []
  (atom {:store (store/empty-store) :image (repl/start!)}))

(defn close! [session]
  (repl/stop! (:image @session))
  nil)

(defn ingest!
  "Ingest `source` as `ns-sym` and load it into the live image."
  [session ns-sym source]
  (swap! session update :store store/ingest ns-sym source)
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
    (when-not (:error r) (reset! session (:system r)))
    r))

(defn test-run!
  "Run `ns-sym`'s tests in the image, recording the result (C4)."
  [session ns-sym]
  (let [res (image/test-run (:image @session) ns-sym)]
    (swap! session update :store store/record-verification ns-sym res)
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
