(ns slopp.refactor
  "Coordinated structural rewrites (Phase-3 ops; rename first).

  Rename is POSITION-BASED: clj-kondo resolves every reference (the def's name
  token + each var usage, including alias-qualified cross-ns uses), and only the
  symbol tokens at those exact positions are rewritten — so a local that shadows
  the var is never touched, the failure mode of string-replace renames.

  Known limitation (documented, Phase-1): symbols inside `:refer` vectors are
  not usage sites in clj-kondo's var-usages and are not rewritten."
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]
            [slopp.store :as store]
            [slopp.render :as render]
            [slopp.index :as index]))

(defn- sites-in-analysis
  "[row col] positions (in the analyzed source) where `def-ns/def-name` is
  written: its definition's name token (when this IS the defining ns) plus every
  resolved usage."
  [analysis def-ns def-name defining-ns?]
  (concat
   (when defining-ns?
     (for [d (:var-definitions analysis)
           :when (and (= def-ns (:ns d)) (= def-name (:name d)))]
       [(:name-row d) (:name-col d)]))
   ;; for a call `(helper x)` kondo's :row/:col point at the call's paren; the
   ;; symbol token itself is at :name-row/:name-col
   (for [u (:var-usages analysis)
         :when (and (= def-ns (:to u)) (= def-name (:name u)))]
     [(or (:name-row u) (:row u)) (or (:name-col u) (:col u))])))

(defn- owner-idx
  "Index of the element (by its start offset) containing position [r c]."
  [offsets [r c]]
  (dec (count (take-while (fn [[er ec]]
                            (or (< er r) (and (= er r) (<= ec c))))
                          offsets))))

(defn- relative
  "Element-local position of absolute [r c] given the element's start [er ec]."
  [[er ec] [r c]]
  (if (= er r) [1 (inc (- c ec))] [(inc (- r er)) c]))

(defn- renamed-symbol [written new-name]
  (if (namespace written)
    (symbol (namespace written) (str new-name))
    (symbol (str new-name))))

(defn- replace-at
  "Replace the symbol token starting at [row col] in `src` (one form's source)
  with its renamed spelling; returns the new source."
  [src [row col] old-name new-name]
  (let [zloc (->> (z/of-string src {:track-position? true})
                  (iterate z/next)
                  (take-while (complement z/end?))
                  (filter #(and (= [row col] (z/position %))
                                (= :token (z/tag %))
                                (symbol? (z/sexpr %))
                                (= (str old-name) (name (z/sexpr %)))))
                  first)]
    (assert zloc (str "rename: no `" old-name "` token at " row ":" col))
    (z/root-string
     (z/replace zloc (n/token-node (renamed-symbol (z/sexpr zloc) new-name))))))

(defn- rewrite-form
  "Rewrite one form's node at the given element-local positions (applied in
  descending position order so earlier positions stay valid)."
  [node positions old-name new-name]
  (let [src' (reduce (fn [src pos] (replace-at src pos old-name new-name))
                     (n/string node)
                     (sort-by (fn [[r c]] [(- r) (- c)]) (distinct positions)))
        nodes (n/children (p/parse-string-all src'))]
    (assert (= 1 (count nodes)) "rename: form no longer parses to one node")
    (first nodes)))

(defn rename-changeset
  "Compute {form-id new-node} renaming `def-ns/old-name` to `new-name` across
  every store namespace."
  [store def-ns old-name new-name]
  (into {}
        (mapcat (fn [ns-sym]
                  (let [an      (index/analyze (render/render-ns store ns-sym))
                        sites   (sites-in-analysis an def-ns old-name (= ns-sym def-ns))
                        offsets (render/element-offsets store ns-sym)
                        elems   (store/elements store ns-sym)]
                    (for [[idx ss] (group-by #(owner-idx offsets %) sites)
                          :let [e (nth elems idx)]]
                      [(:id e)
                       (rewrite-form (:node e)
                                     (map #(relative (nth offsets idx) %) ss)
                                     old-name new-name)]))))
        (keys (:namespaces store))))
