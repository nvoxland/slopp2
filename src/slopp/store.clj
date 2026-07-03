(ns slopp.store
  "In-memory form store + append-only delta log — the system of record (C2/C3/C4).

  A namespace is an ordered sequence of *elements*: each element is either a
  semantic `:form` (a top-level sexpr, carrying a stable synthetic id + derived
  name + its rewrite-clj CST node) or a `:sep` (whitespace/comment/newline node
  kept only so rendering is lossless). Forms are the identified, versioned units;
  separators are incidental trivia (a Phase-1 simplification — a later model may
  attach leading trivia to the form it precedes).

  Ids are a monotonic counter here (single-agent Phase 1). Phase-4 multi-agent
  needs globally-unique ids (uuid / lamport)."
  (:require [clojure.string :as str]
            [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]))

(defn empty-store []
  {:namespaces {} :deltas [] :next-id 0})

(defn- gen-id [store prefix]
  (let [i (:next-id store)]
    [(str prefix i) (assoc store :next-id (inc i))]))

(defn alloc-id
  "Public id allocation (e.g. a `g<n>` group id shared by several deltas)."
  [store prefix]
  (gen-id store prefix))

(def ^:private def-heads
  "Head symbols whose second element names the form."
  '#{def defn defn- defmacro defmulti defmethod defrecord deftype
     defprotocol defonce deftest ns})

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
  [store ns-sym source & {:keys [agent]}]
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
                      (cond-> {:id did :parent nil :op :ingest :ns ns-sym
                               :form-ids (into [] (keep :id) elements)
                               ;; per-version content (C3/C4): history must be
                               ;; reconstructible from the log alone
                               :sources  (into {}
                                               (keep (fn [e]
                                                       (when (:id e)
                                                         [(:id e) (n/string (:node e))])))
                                               elements)}
                        agent (assoc :agent agent)))))))))

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
  [store ns-sym nm node & {:keys [prompt op group agent] :or {op :replace}}]
  (let [elems (get-in store [:namespaces ns-sym :elements])
        idx   (first (keep-indexed
                      (fn [i e] (when (and (= :form (:kind e)) (= nm (:name e))) i))
                      elems))]
    (when idx
      (let [elem     (nth elems idx)
            new-elem (assoc elem :node node :name (form-symbol node))
            [did store] (gen-id store "d")
            delta    (cond-> {:id did :parent (:id (last (:deltas store)))
                              :op op :ns ns-sym :form-id (:id elem) :prompt prompt
                              :sources {(:id elem) (n/string node)}}
                       group (assoc :group group)
                       agent (assoc :agent agent))]
        [(-> store
             (assoc-in [:namespaces ns-sym :elements] (assoc elems idx new-elem))
             (update :deltas conj delta))
         delta]))))

(defn append-form
  "Append a new form to `ns-sym` (separated by a newline, followed by one) with
  a fresh id; ONE `:add` delta. Returns [store' delta], or nil if the namespace
  doesn't exist."
  [store ns-sym node & {:keys [prompt group agent]}]
  (when-let [elems (get-in store [:namespaces ns-sym :elements])]
    (let [needs-nl?    (and (seq elems)
                            (not (str/ends-with? (n/string (:node (peek elems))) "\n")))
          [fid store]  (gen-id store "f")
          [did store'] (gen-id store "d")
          new-elems    (cond-> elems
                         needs-nl? (conj {:kind :sep :node (n/newlines 1)})
                         true      (conj {:id fid :kind :form
                                          :name (form-symbol node) :node node}
                                         {:kind :sep :node (n/newlines 1)}))
          delta        (cond-> {:id did :parent (:id (last (:deltas store)))
                                :op :add :ns ns-sym :form-id fid :prompt prompt
                                :sources {fid (n/string node)}}
                         group (assoc :group group)
                         agent (assoc :agent agent))]
      [(-> store'
           (assoc-in [:namespaces ns-sym :elements] new-elems)
           (update :deltas conj delta))
       delta])))

(defn remove-form
  "Remove the form named `nm` from `ns-sym` (plus its immediately following
  separator, so no doubled blank line remains); ONE `:delete` delta. Returns
  [store' delta], or nil if no such form."
  [store ns-sym nm & {:keys [prompt group agent]}]
  (let [elems (get-in store [:namespaces ns-sym :elements])
        idx   (first (keep-indexed
                      (fn [i e] (when (and (= :form (:kind e)) (= nm (:name e))) i))
                      elems))]
    (when idx
      (let [fid          (:id (nth elems idx))
            drop-next?   (and (< (inc idx) (count elems))
                              (= :sep (:kind (nth elems (inc idx)))))
            new-elems    (into (subvec elems 0 idx)
                               (subvec elems (+ idx (if drop-next? 2 1))))
            [did store'] (gen-id store "d")
            delta        (cond-> {:id did :parent (:id (last (:deltas store)))
                                  :op :delete :ns ns-sym :form-id fid :name nm
                                  :removed-source (n/string (:node (nth elems idx)))
                                  :prompt prompt}
                           group (assoc :group group)
                           agent (assoc :agent agent))]
        [(-> store'
             (assoc-in [:namespaces ns-sym :elements] new-elems)
             (update :deltas conj delta))
         delta]))))

(defn move-form
  "Move the form named `nm` (with its trailing separator) to just before the
  form named `before-nm` (S2 — fixes append-only forward references). ONE
  `:move` delta. Returns [store' delta], or nil if either form is missing."
  [store ns-sym nm before-nm & {:keys [prompt group agent]}]
  (let [elems  (get-in store [:namespaces ns-sym :elements])
        idx-of (fn [es n]
                 (first (keep-indexed
                         (fn [i e] (when (and (= :form (:kind e)) (= n (:name e))) i))
                         es)))
        i (idx-of elems nm)
        j (idx-of elems before-nm)]
    (when (and i j (not= nm before-nm))
      (let [unit-end  (if (and (< (inc i) (count elems))
                               (= :sep (:kind (nth elems (inc i)))))
                        (+ i 2)
                        (inc i))
            unit      (subvec elems i unit-end)
            without   (into (subvec elems 0 i) (subvec elems unit-end))
            j'        (idx-of without before-nm)
            new-elems (-> (subvec without 0 j')
                          (into unit)
                          (into (subvec without j')))
            [did store'] (gen-id store "d")
            delta (cond-> {:id did :parent (:id (last (:deltas store)))
                           :op :move :ns ns-sym
                           :form-id (:id (nth elems i)) :before before-nm
                           :prompt prompt}
                    group (assoc :group group)
                    agent (assoc :agent agent))]
        [(-> store'
             (assoc-in [:namespaces ns-sym :elements] new-elems)
             (update :deltas conj delta))
         delta]))))

(defn- ns-requires
  "Store namespaces required by `ns-sym`'s ns form."
  [store ns-sym]
  (when-let [e (form-named store ns-sym ns-sym)]
    (let [s (n/sexpr (:node e))]
      (for [clause s
            :when (and (seq? clause) (= :require (first clause)))
            spec (rest clause)
            :let [lib (if (vector? spec) (first spec) spec)]
            :when (contains? (:namespaces store) lib)]
        lib))))

(defn ns-closure
  "`ns-sym` plus every store namespace it transitively requires — the scope a
  test run in `ns-sym` can reach (item 2: instrumenting ALL namespaces made
  per-write verification cost grow with total store size)."
  [store ns-sym]
  (loop [seen #{} frontier [ns-sym]]
    (if-let [n (first frontier)]
      (if (seen n)
        (recur seen (subvec frontier 1))
        (recur (conj seen n)
               (into (subvec frontier 1) (ns-requires store n))))
      seen)))

(defn ns-dependency-order
  "Every store namespace, dependencies first (X3): image loads MUST use this —
  a plain (keys (:namespaces store)) goes hash-ordered past 8 entries, which
  silently half-loaded 12-namespace images in eval round 3. Deterministic:
  ties break by sorted name; cycles fall back to sorted remainder."
  [store]
  (let [deps (into {}
                   (map (fn [n] [n (set (ns-requires store n))]))
                   (keys (:namespaces store)))]
    (loop [result [], remaining (vec (sort (keys deps))), done #{}]
      (if (empty? remaining)
        result
        (if-let [ready (first (filter #(every? done (deps %)) remaining))]
          (recur (conj result ready)
                 (vec (remove #{ready} remaining))
                 (conj done ready))
          (into result remaining))))))

(defn ns-of-form-id
  "The namespace whose elements contain the form with `id`, or nil."
  [store id]
  (some (fn [[ns-sym {:keys [elements]}]]
          (when (some #(= id (:id %)) elements) ns-sym))
        (:namespaces store)))

(defn apply-changeset
  "Coordinated multi-form edit (e.g. rename): replace several forms' nodes —
  possibly across namespaces — as ONE delta. `changeset` = {form-id new-node}.
  `extra` is merged into the delta (e.g. {:old .. :new ..}). Returns
  [store' delta]."
  [store op ns-sym changeset & {:keys [prompt extra agent]}]
  (let [[did store'] (gen-id store "d")
        delta (merge (cond-> {:id did :parent (:id (last (:deltas store)))
                              :op op :ns ns-sym
                              :form-ids (vec (sort (keys changeset)))
                              :sources  (into {} (map (fn [[fid node]]
                                                        [fid (n/string node)]))
                                              changeset)
                              :prompt prompt}
                       agent (assoc :agent agent))
                     extra)
        store' (reduce-kv
                (fn [st ns-key {:keys [elements]}]
                  (assoc-in st [:namespaces ns-key :elements]
                            (mapv (fn [e]
                                    (if-let [node (get changeset (:id e))]
                                      (assoc e :node node :name (form-symbol node))
                                      e))
                                  elements)))
                store' (:namespaces store'))]
    [(update store' :deltas conj delta) delta]))

(defn record-checkpoint
  "Append a `:checkpoint` boundary delta — a unit-of-work marker (and the
  close of `agent`'s episode). Returns [store' delta-id]."
  [store label & {:keys [agent]}]
  (let [[did store'] (gen-id store "d")]
    [(update store' :deltas conj
             (cond-> {:id did :parent (:id (last (:deltas store)))
                      :op :checkpoint :ns '*session*}
               label (assoc :label label)
               agent (assoc :agent agent)))
     did]))

(defn sources-at
  "The {form-id source-text} content view as of delta `at-id` (inclusive;
  nil = before any delta). Reconstructed from the log — powers episode
  diffs."
  [store at-id]
  (if (nil? at-id)
    {}
    (reduce (fn [acc d]
              (let [acc (merge acc (:sources d))
                    acc (if (= :delete (:op d)) (dissoc acc (:form-id d)) acc)]
                (if (= at-id (:id d)) (reduced acc) acc)))
            {}
            (:deltas store))))

(defn record-verification
  "Append a `:verify` delta recording a test-run result against `ns-sym` — 'what
  was proven green at this point' (C4, D5/D6 verification-provenance)."
  [store ns-sym result]
  (let [parent (:id (last (:deltas store)))
        [did store] (gen-id store "d")]
    (update store :deltas conj
            {:id did :parent parent :op :verify :ns ns-sym :result result})))

;; --- Phase 4 m2: the CRDT merge -------------------------------------------

(defn- suffix-touched
  "Form ids touched by content deltas in a delta seq."
  [ds]
  (into #{}
        (mapcat (fn [d]
                  (concat (when (:form-id d) [(:form-id d)])
                          (:form-ids d)
                          (keys (:sources d)))))
        ds))

(defn- qform [store ns-sym fid fallback-store]
  (let [e (or (form-by-id store fid) (form-by-id fallback-store fid))]
    (symbol (str ns-sym) (str (or (:name e) fid)))))

(defn record-merge
  "Append a `:merge` delta — what arrived, from where, the surfaced conflicts
  (MV records the agent resolves by hand), and `:applied` — the ids of THEIR
  deltas now causally delivered here, which is what keeps iterated merges
  exact. Returns [store' delta]."
  [store from {:keys [merged conflicts new-nses applied id-map]}]
  (let [[did store'] (gen-id store "d")
        delta (cond-> {:id did :parent (:id (last (:deltas store)))
                       :op :merge :ns '*session* :from (str from)
                       :merged merged}
                (seq applied)   (assoc :applied (vec applied))
                (seq id-map)    (assoc :id-map id-map)
                (seq conflicts) (assoc :conflicts (mapv #(dissoc % :ours) conflicts))
                (seq new-nses)  (assoc :new-nses (vec new-nses)))]
    [(update store' :deltas conj delta) delta]))

(defn- tag-merged
  "Mark the just-appended delta as a replay of THEIR delta `their-id` — it is
  their work, not ours, and later merges must treat it that way."
  [store their-id]
  (update store :deltas
          (fn [ds] (conj (pop ds) (assoc (peek ds) :merged-from their-id)))))

(defn merge-logs
  "Phase 4 m2 (C4/C5 activation): merge `theirs` — a store sharing a common
  delta-log prefix with `ours` (a fork = a copied project dir) — into ours by
  REPLAYING theirs' suffix, form-id-keyed:
  - different-form work merges clean (the granularity dodge, across replicas)
  - identical changes converge silently
  - same-form divergence = MV conflict: ours kept, theirs surfaced
  - add/add id collisions are remapped to fresh ids
  - whole namespaces created on their side arrive intact (provenance kept)
  - :move deltas are skipped with a note (ordering is cosmetic in the image)
  Iterated merges stay exact via causal delivery: replayed deltas carry
  :merged-from (their id), the :merge delta records :applied, and neither
  replays again nor counts as OUR work in conflict detection.
  Returns {:store :merged :conflicts :notes :changed-form-ids :new-nses
           :applied :fork-point} — pure; the caller owns image loads +
  verification."
  [ours theirs & {:keys [from]}]
  (let [od         (:deltas ours)
        td         (:deltas theirs)
        ;; full-value comparison: both sides allocate the same NEXT id for
        ;; different work, so id equality would swallow the first divergence
        common     (count (take-while true? (map = od td)))
        fork-point (:id (last (take common od)))
        ours-sfx   (drop common od)
        ;; causal state from PRIOR merges of THIS source (delta ids collide
        ;; across different sources, so scope by :from): what's delivered,
        ;; and how their form ids were remapped into ours
        prior      (filter #(and (= :merge (:op %)) (= (str from) (:from %)))
                           od)
        delivered  (into #{} (mapcat :applied) prior)
        idmap0     (into {} (mapcat :id-map) prior)
        dropped    (filter #(delivered (:id %)) (drop common td))
        ;; recreated-source guard: a "delivered" delta whose content doesn't
        ;; match OUR replayed copy of it means the source was deleted and
        ;; recreated at the same path/name — its ids alias dead history, and
        ;; silently dropping its work would be corruption
        imposter   (some (fn [d]
                           (when-let [copy (first (filter #(= (:id d)
                                                              (:merged-from %))
                                                          od))]
                             ;; compare CONTENT — replay remaps the form-id
                             ;; keys, so only the source texts are stable
                             (when (and (:sources d)
                                        (not= (sort (vals (:sources d)))
                                              (sort (vals (:sources copy)))))
                               d)))
                         dropped)
        theirs-sfx (remove #(delivered (:id %)) (drop common td))
        touched    (suffix-touched (remove :merged-from ours-sfx))]
    (if imposter
      {:error (str "merge identity mismatch: delta " (:id imposter)
                   " looks like a recreated fork/branch at the same"
                   " path/name — use a fresh path (or a new branch)")}
      (loop [st ours, dds (seq theirs-sfx), idmap idmap0, merged 0,
           conflicts [], notes [], changed [], new-nses [], applied []]
      (if-let [d (first dds)]
        (let [ds        (rest dds)
              op        (:op d)
              done      (fn [st idmap merged conflicts notes changed new-nses applied]
                          [st idmap merged conflicts notes changed new-nses applied])
              [st idmap merged conflicts notes changed new-nses applied]
              (case op
                (:verify :checkpoint :merge)
                (done st idmap merged conflicts notes changed new-nses applied)

                :ingest
                (let [ns-sym (:ns d)]
                  (if (get-in st [:namespaces ns-sym])
                    (done st idmap merged conflicts
                          (conj notes {:skipped :ingest :ns ns-sym
                                       :reason "namespace exists on our side"})
                          changed new-nses (conj applied (:id d)))
                    (let [src (apply str (map #(str (get (:sources d) %) "\n")
                                              (:form-ids d)))
                          st' (tag-merged (ingest st ns-sym src :agent (:agent d))
                                          (:id d))
                          new-ids (into [] (keep :id) (elements st' ns-sym))]
                      (done st' (merge idmap (zipmap (:form-ids d) new-ids))
                            (inc merged) conflicts notes changed
                            (conj new-nses ns-sym) (conj applied (:id d))))))

                :add
                (let [ns-sym (:ns d)
                      fid    (:form-id d)
                      src    (get (:sources d) fid)
                      node   (p/parse-string src)
                      nm     (form-symbol node)
                      cur    (when nm (form-named st ns-sym nm))]
                  (cond
                    (and cur (= (n/string (:node cur)) src)) ; converged
                    (done st (assoc idmap fid (:id cur)) merged conflicts notes
                          changed new-nses (conj applied (:id d)))

                    cur                                      ; name clash
                    (done st idmap merged
                          (conj conflicts {:form (symbol (str ns-sym) (str nm))
                                           :ns ns-sym :delta (:id d)
                                           :ours (n/string (:node cur))
                                           :theirs src
                                           :reason "both sides added this name"})
                          notes changed new-nses applied)

                    :else
                    (if-let [[st' d'] (append-form st ns-sym node
                                                   :prompt (:prompt d)
                                                   :agent (:agent d))]
                      (done (tag-merged st' (:id d))
                            (assoc idmap fid (:form-id d'))
                            (inc merged) conflicts notes
                            (conj changed (:form-id d')) new-nses
                            (conj applied (:id d)))
                      (done st idmap merged conflicts
                            (conj notes {:skipped :add :reason "no namespace"
                                         :ns ns-sym})
                            changed new-nses (conj applied (:id d))))))

                :replace
                (let [ns-sym (:ns d)
                      fid0   (:form-id d)
                      fid    (get idmap fid0 fid0)
                      src    (get (:sources d) fid0)
                      cur    (form-by-id st fid)]
                  (cond
                    (nil? cur)                               ; deleted on our side
                    (done st idmap merged
                          (conj conflicts {:form (qform st ns-sym fid0 theirs)
                                           :ns ns-sym :delta (:id d)
                                           :ours nil :theirs src
                                           :reason "we deleted it; they edited it"})
                          notes changed new-nses applied)

                    (= (n/string (:node cur)) src)           ; converged
                    (done st idmap merged conflicts notes changed new-nses
                          (conj applied (:id d)))

                    (and (touched fid) (not (contains? (set changed) fid)))
                    (done st idmap merged
                          (conj conflicts {:form (qform st ns-sym fid0 theirs)
                                           :ns ns-sym :delta (:id d)
                                           :ours (n/string (:node cur))
                                           :theirs src
                                           :reason "both sides edited this form"})
                          notes changed new-nses applied)

                    (nil? (:name cur))
                    (done st idmap merged conflicts
                          (conj notes {:skipped :replace :form fid
                                       :reason "anonymous form"})
                          changed new-nses (conj applied (:id d)))

                    :else
                    (let [[st' _] (replace-node st ns-sym (:name cur)
                                                (p/parse-string src)
                                                :prompt (:prompt d)
                                                :agent (:agent d))]
                      (done (tag-merged st' (:id d)) idmap (inc merged)
                            conflicts notes (conj changed fid) new-nses
                            (conj applied (:id d))))))

                :delete
                (let [ns-sym (:ns d)
                      fid0   (:form-id d)
                      fid    (get idmap fid0 fid0)
                      cur    (form-by-id st fid)]
                  (cond
                    (nil? cur)                               ; converged
                    (done st idmap merged conflicts notes changed new-nses
                          (conj applied (:id d)))

                    (touched fid)
                    (done st idmap merged
                          (conj conflicts {:form (qform st ns-sym fid0 theirs)
                                           :ns ns-sym :delta (:id d)
                                           :ours (n/string (:node cur)) :theirs nil
                                           :reason "we edited it; they deleted it"})
                          notes changed new-nses applied)

                    :else
                    (let [[st' _] (remove-form st ns-sym (:name cur)
                                               :prompt (:prompt d)
                                               :agent (:agent d))]
                      (done (tag-merged st' (:id d)) idmap (inc merged)
                            conflicts notes changed new-nses
                            (conj applied (:id d))))))

                (:rename :normalize)
                ;; changeset ops are all-or-conflict: a partially applied
                ;; rename would leave broken references
                (let [srcs (:sources d)
                      fids (map #(get idmap % %) (keys srcs))
                      blocked (filter #(and (touched %)
                                            (not (contains? (set changed) %)))
                                      fids)]
                  (if (seq blocked)
                    (done st idmap merged
                          (conj conflicts {:form (qform st (:ns d)
                                                        (first blocked) theirs)
                                           :ns (:ns d) :delta (:id d)
                                           :theirs (pr-str (select-keys d [:old :new]))
                                           :reason "their rename touches forms we edited"})
                          notes changed new-nses applied)
                    (let [changeset (into {}
                                          (keep (fn [[fid0 src]]
                                                  (let [fid (get idmap fid0 fid0)]
                                                    (when (form-by-id st fid)
                                                      [fid (p/parse-string src)]))))
                                          srcs)
                          [st' _] (apply-changeset st (:op d) (:ns d) changeset
                                                   :prompt (:prompt d)
                                                   :agent (:agent d)
                                                   :extra (select-keys d [:old :new]))]
                      (done (tag-merged st' (:id d)) idmap (inc merged)
                            conflicts notes (into changed (keys changeset))
                            new-nses (conj applied (:id d))))))

                :move
                (done st idmap merged conflicts
                      (conj notes {:skipped :move :ns (:ns d)
                                   :reason "ordering is cosmetic; re-run edit_move if wanted"})
                      changed new-nses (conj applied (:id d)))

                ;; unknown op: never guess with someone's code
                (done st idmap merged conflicts
                      (conj notes {:skipped (:op d) :delta (:id d)})
                      changed new-nses (conj applied (:id d))))]
          (recur st ds idmap merged conflicts notes changed new-nses applied))
        {:store st :merged merged :conflicts conflicts :notes notes
         :changed-form-ids (vec (distinct changed)) :new-nses new-nses
         :applied applied :id-map idmap :fork-point fork-point})))))
