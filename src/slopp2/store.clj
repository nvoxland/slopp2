(ns slopp2.store
  "In-memory form store + append-only delta log — the system of record (C2/C3/C4).

  A namespace is an ordered sequence of *elements*: each element is either a
  semantic `:form` (a top-level sexpr, carrying a stable synthetic id + derived
  name + its rewrite-clj CST node) or a `:sep` (whitespace/comment/newline node
  kept only so rendering is lossless). Forms are the identified, versioned units;
  separators are incidental trivia (a Phase-1 simplification — a later model may
  attach leading trivia to the form it precedes).

  Ids are a monotonic counter here (single-agent Phase 1). Phase-4 multi-agent
  needs globally-unique ids (uuid / lamport)."
  (:require [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]))

(defn empty-store []
  {:namespaces {} :deltas [] :next-id 0})

(defn- gen-id [store prefix]
  (let [i (:next-id store)]
    [(str prefix i) (assoc store :next-id (inc i))]))

(def ^:private def-heads
  "Head symbols whose second element names the form."
  '#{def defn defn- defmacro defmulti defmethod defrecord deftype
     defprotocol defonce ns})

(defn form-symbol
  "The symbol a top-level form defines, or nil (anonymous/effectful top-levels)."
  [node]
  (when (and (n/sexpr-able? node) (= :list (n/tag node)))
    (let [s (n/sexpr node)]
      (when (and (seq s) (symbol? (first s)) (contains? def-heads (first s)))
        (let [nm (second s)]
          (when (symbol? nm) nm))))))

(defn ingest
  "Parse `source` into `ns-sym`'s ordered elements, assigning a fresh id to each
  form, and append an `:ingest` delta. Returns the new store."
  [store ns-sym source]
  (let [nodes (n/children (p/parse-string-all source))]
    (loop [store store, nodes nodes, elements []]
      (if-let [node (first nodes)]
        (if (n/sexpr-able? node)
          (let [[id store] (gen-id store "f")]
            (recur store (rest nodes)
                   (conj elements {:id id :kind :form
                                   :name (form-symbol node) :node node})))
          (recur store (rest nodes)
                 (conj elements {:kind :sep :node node})))
        (let [[did store] (gen-id store "d")]
          (-> store
              (assoc-in [:namespaces ns-sym :elements] elements)
              (update :deltas conj
                      {:id did :parent nil :op :ingest :ns ns-sym
                       :form-ids (into [] (keep :id) elements)})))))))

(defn elements
  "All elements of `ns-sym` in order (forms + separators)."
  [store ns-sym]
  (get-in store [:namespaces ns-sym :elements]))

(defn forms
  "Ordered semantic forms of `ns-sym` (separators dropped)."
  [store ns-sym]
  (filterv #(= :form (:kind %)) (elements store ns-sym)))

(defn form-named
  "The form in `ns-sym` defining symbol `nm`, or nil."
  [store ns-sym nm]
  (first (filter #(= nm (:name %)) (forms store ns-sym))))

(defn form-by-id
  "The form anywhere in the store with the given id, or nil."
  [store id]
  (->> (:namespaces store)
       (mapcat (comp :elements val))
       (filter #(= id (:id %)))
       first))

(defn deltas [store] (:deltas store))

(defn replace-node
  "Replace the CST node of the form named `nm` in `ns-sym`, keeping its stable id
  (C2/O1 whole-form replace); append a `:replace` delta carrying `prompt`.
  Returns [store' delta], or nil if no such form."
  [store ns-sym nm node & {:keys [prompt op] :or {op :replace}}]
  (let [elems (get-in store [:namespaces ns-sym :elements])
        idx   (first (keep-indexed
                      (fn [i e] (when (and (= :form (:kind e)) (= nm (:name e))) i))
                      elems))]
    (when idx
      (let [elem     (nth elems idx)
            new-elem (assoc elem :node node :name (form-symbol node))
            [did store] (gen-id store "d")
            delta    {:id did :parent (:id (last (:deltas store)))
                      :op op :ns ns-sym :form-id (:id elem) :prompt prompt}]
        [(-> store
             (assoc-in [:namespaces ns-sym :elements] (assoc elems idx new-elem))
             (update :deltas conj delta))
         delta]))))

(defn record-verification
  "Append a `:verify` delta recording a test-run result against `ns-sym` — 'what
  was proven green at this point' (C4, D5/D6 verification-provenance)."
  [store ns-sym result]
  (let [parent (:id (last (:deltas store)))
        [did store] (gen-id store "d")]
    (update store :deltas conj
            {:id did :parent parent :op :verify :ns ns-sym :result result})))
