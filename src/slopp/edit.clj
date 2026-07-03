(ns slopp.edit
  "The edit pipeline (O1 whole-form replace): parse -> dialect-check (D3/D4) ->
  commit delta (D-store) -> hot-reload into the image (D5) -> return with
  `!`-effect warnings (D6). Verification (affected tests + restart-as-diagnostic)
  is orchestrated one level up, in slopp.api."
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.render :as render]
            [slopp.index :as index]
            [slopp.repl :as repl]))

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
  "Pipeline through hot-reload over `system` {:store store :image handle}:
  `replace-form`, then on success redefine the form in the live image (D5).
  Returns {:system {:store ...} :delta :warnings} or {:error msg}."
  [system ns-sym form-name new-source & {:keys [prompt]}]
  (let [r (replace-form (:store system) ns-sym form-name new-source :prompt prompt)]
    (if (:error r)
      r
      (let [image (:image system)]
        (repl/eval! image (format "(in-ns '%s)" ns-sym))
        (repl/eval! image new-source)                 ; hot-redefine the var
        {:system   (assoc system :store (:store r))
         :delta    (:delta r)
         :warnings (:warnings r)}))))
