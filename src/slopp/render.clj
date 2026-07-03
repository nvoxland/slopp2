(ns slopp.render
  "VFS render: project a namespace's current source from the store on demand
  (C1/C6). Lossless — concatenating each element's CST string reproduces the
  ingested source exactly. This is what tools/agents 'read'; nothing is written
  to disk unless an explicit build asks."
  (:require [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store]))

(defn render-ns
  "Render `ns-sym`'s current source as a string from the store."
  [store ns-sym]
  (apply str (map (comp n/string :node) (store/elements store ns-sym))))

(defn ns-path
  "The VFS path of a namespace's rendered file (also used by build! and as the
  source-path for image loads, so stack traces cite VFS coordinates — F6)."
  [ns-sym]
  (str (-> (str ns-sym) (str/replace "-" "_") (str/replace "." "/")) ".clj"))

(declare element-offsets)

(defn owner-form
  "The form element whose rendered span contains position [row col], or nil —
  the bridge from linter/index positions to form addressing."
  [store ns-sym row col]
  (let [elems   (store/elements store ns-sym)
        offsets (element-offsets store ns-sym)
        idx     (dec (count (take-while (fn [[er ec]]
                                          (or (< er row)
                                              (and (= er row) (<= ec col))))
                                        offsets)))]
    (when-not (neg? idx)
      (let [e (nth elems idx)]
        (when (= :form (:kind e)) e)))))

(defn element-offsets
  "Start position [row col] (1-based) of each of `ns-sym`'s elements within the
  rendered source — the bridge from index positions (clj-kondo rows/cols against
  `render-ns` output) back to the owning store element."
  [store ns-sym]
  (loop [es (store/elements store ns-sym), row 1, col 1, acc []]
    (if-let [e (first es)]
      (let [s  (n/string (:node e))
            nl (count (filter #(= \newline %) s))]
        (recur (rest es)
               (+ row nl)
               (if (pos? nl)
                 (inc (count (subs s (inc (str/last-index-of s "\n")))))
                 (+ col (count s)))
               (conj acc [row col])))
      acc)))
