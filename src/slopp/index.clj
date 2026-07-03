(ns slopp.index
  "Static semantic index over rendered source via clj-kondo (content-fed through
  stdin — no disk, C1/C6): var definitions, references, the call graph, and the
  `!`-effect analysis (D6).

  `!`-effect (D6): a var is effectful iff it can transitively reach a known
  effectful primitive through the call graph — a sound, decidable check for
  first-order code (higher-order/effect-polymorphic cases are left to the live
  oracle, D5). The dialect requires the `!` in a var's name to match its computed
  effectfulness; mismatches are reported for the edit pipeline to flag/auto-fix."
  (:require [clojure.string :as str]
            [clj-kondo.core :as kondo]))

(def effectful-leaves
  "Fixed anchor set: core primitives that modify in-process or external state
  (D6 scope = modification). Extensible; reads / non-determinism are NOT here."
  '#{clojure.core/swap! clojure.core/reset! clojure.core/swap-vals!
     clojure.core/reset-vals! clojure.core/compare-and-set!
     clojure.core/vreset! clojure.core/vswap!
     clojure.core/alter clojure.core/ref-set clojure.core/alter-var-root
     clojure.core/commute clojure.core/send clojure.core/send-off
     clojure.core/deliver clojure.core/conj! clojure.core/disj!
     clojure.core/assoc! clojure.core/dissoc! clojure.core/pop!
     clojure.core/spit clojure.core/delete-file})

(defn analyze
  "Run clj-kondo over `source` (fed via stdin, no disk); return its `:analysis`
  ({:var-definitions :var-usages :namespace-definitions :namespace-usages})."
  [source]
  (:analysis
   (with-in-str source
     (kondo/run! {:lint ["-"] :config {:output {:analysis true}}}))))

(defn- node
  "Fully-qualified node key for a var: ns/name."
  [ns nm]
  (symbol (str ns) (str nm)))

(defn call-graph
  "Map of caller-node -> #{callee-node}, over user vars (top-level usages, whose
  `:from-var` is nil, are skipped)."
  [analysis]
  (reduce (fn [m u]
            (if (:from-var u)
              (update m (node (:from u) (:from-var u))
                      (fnil conj #{}) (node (:to u) (:name u)))
              m))
          {}
          (:var-usages analysis)))

(defn effectful-vars
  "Set of user var nodes that transitively reach an effectful leaf (D6).
  Monotonic fixpoint — cycle-safe."
  [analysis]
  (let [edges (call-graph analysis)]
    (loop [eff (set (for [[n ts] edges :when (some effectful-leaves ts)] n))]
      (let [eff' (into eff (for [[n ts] edges :when (some eff ts)] n))]
        (if (= eff eff') eff (recur eff'))))))

(defn- bang? [nm] (str/ends-with? (str nm) "!"))

(defn test-definition?
  "Is this var-definition a test (deftest)? Tests are exempt from the `!`
  naming rule (T1) — they routinely exercise effectful code but are never
  bang-named by convention."
  [d]
  (= 'clojure.test/deftest (:defined-by d)))

(defn effect-violations
  "Vars whose `!`-naming disagrees with their computed effectfulness (D6). Each:
  {:var node :effectful? bool :named-bang? bool :suggest new-name-string}.
  deftest vars are exempt (T1)."
  [analysis]
  (let [eff (effectful-vars analysis)]
    (for [d (:var-definitions analysis)
          :when (not (test-definition? d))
          :let [n         (node (:ns d) (:name d))
                effectful (contains? eff n)
                named     (bang? (:name d))]
          :when (not= effectful named)]
      {:var n :effectful? effectful :named-bang? named
       :suggest (if effectful
                  (str (:name d) "!")
                  (str/replace (str (:name d)) #"!+$" ""))})))

(defn analyze-with-locals
  "Like `analyze`, but including local-binding definitions and usages
  (`:locals` / `:local-usages`, linked by `:id`) — the basis for free-variable
  computation in structural extraction."
  [source]
  (:analysis
   (with-in-str source
     (kondo/run! {:lint ["-"]
                  :config {:analysis {:locals true} :output {:analysis true}}}))))

(defn lint
  "clj-kondo FINDINGS for `source` (syntax + best-practice violations, distinct
  from the :analysis extraction): [{:level :type :message :row :col} ...],
  warnings and errors only."
  [source]
  (->> (:findings (with-in-str source (kondo/run! {:lint ["-"]})))
       (filter #(#{:warning :error} (:level %)))
       (mapv #(select-keys % [:level :type :message :row :col]))))

(defn references
  "Usages of `to-ns/to-name` — who references this var."
  [analysis to-ns to-name]
  (for [u (:var-usages analysis)
        :when (and (= to-ns (:to u)) (= to-name (:name u)))]
    {:from-ns (:from u) :from-var (:from-var u) :row (:row u) :col (:col u)}))
