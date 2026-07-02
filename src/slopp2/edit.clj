(ns slopp2.edit
  "The edit pipeline (O1 whole-form replace): parse -> dialect-check (D3/D4) ->
  commit delta (D-store) -> [hot-reload into the image (D5) + run affected tests
  (C4)] -> return with `!`-effect warnings (D6). This is the authoring loop where
  every prior decision converges."
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [slopp2.store :as store]
            [slopp2.render :as render]
            [slopp2.index :as index]
            [slopp2.repl :as repl]
            [slopp2.image :as image]))

(def ^:private banned-heads
  "D4 — user macros are banned."
  '#{defmacro})

(def ^:private banned-syms
  "D3 — a sample of the analysis-defeater denylist (extensible)."
  '#{eval alter-var-root binding gen-class definline read-string})

(defn- all-symbols [node]
  (filter symbol? (tree-seq coll? seq (n/sexpr node))))

(defn- dialect-check
  "nil if the form is admissible; an error string otherwise (D3/D4)."
  [node]
  (let [s    (n/sexpr node)
        head (when (seq? s) (first s))]
    (cond
      (contains? banned-heads head)
      (str "dialect (D4): user macros are banned — " head)
      (some banned-syms (all-symbols node))
      (str "dialect (D3): denylisted symbol used — "
           (first (filter banned-syms (all-symbols node))))
      :else nil)))

(defn replace-form
  "Pure edit: validate `new-source` (one dialect-legal form) and replace the form
  named `form-name` in `ns-sym`, keeping its id and appending a `:replace` delta.
  Returns {:store :delta :warnings} (warnings = D6 `!`-effect violations of the
  resulting namespace) or {:error msg}."
  [store ns-sym form-name new-source & {:keys [prompt]}]
  (let [forms (filter n/sexpr-able? (n/children (p/parse-string-all new-source)))]
    (if (not= 1 (count forms))
      {:error (str "expected exactly one top-level form, got " (count forms))}
      (let [node (first forms)]
        (if-let [err (dialect-check node)]
          {:error err}
          (if-let [[store' delta] (store/replace-node store ns-sym form-name node
                                                      :prompt prompt)]
            {:store    store'
             :delta    delta
             :warnings (index/effect-violations
                        (index/analyze (render/render-ns store' ns-sym)))}
            {:error (str "no form named " form-name " in " ns-sym)}))))))

(defn apply-replace!
  "Full pipeline over `system` {:store store :image handle}: `replace-form`, then
  on success hot-reload the new form into the live image (D5) and re-run the
  namespace's tests, recording the result as provenance (C4). Returns
  {:system {:store ...} :delta :warnings :test result} or {:error msg}."
  [system ns-sym form-name new-source & {:keys [prompt]}]
  (let [r (replace-form (:store system) ns-sym form-name new-source :prompt prompt)]
    (if (:error r)
      r
      (let [image (:image system)]
        (repl/eval! image (format "(in-ns '%s)" ns-sym))
        (repl/eval! image new-source)                 ; hot-redefine the var
        (let [test-res (image/test-run image ns-sym)
              store'   (store/record-verification (:store r) ns-sym test-res)]
          {:system   (assoc system :store store')
           :delta    (:delta r)
           :warnings (:warnings r)
           :test     test-res})))))
