(ns slopp.normalize-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.normalize :as norm]
            [slopp.store :as store]
            [slopp.api :as api]))

(defn- normed [src] (:src (norm/normalize-source src)))

(deftest rewrite-rules
  (testing "kibit-classics, conservative set"
    (is (= "(when x y)"          (normed "(if x y nil)")))
    (is (= "(when x y)"          (normed "(if x y)")))
    (is (= "(when-not x y)"      (normed "(if x nil y)")))
    (is (= "(boolean x)"         (normed "(if x true false)")))
    (is (= "(if-not x a b)"      (normed "(if (not x) a b)")))
    (is (= "(when-not x a b)"    (normed "(when (not x) a b)")))
    (is (= "(not= a b)"          (normed "(not (= a b))")))
    (is (= "x"                   (normed "(do x)"))))
  (testing "rewrites nest inside surrounding code without disturbing it"
    (is (= "(defn f [x]\n  (if-not x 1 2))"
           (normed "(defn f [x]\n  (if (not x) 1 2))"))))
  (testing "cascading rewrites reach a fixpoint"
    (is (= "(when x y)" (normed "(do (if x y nil))"))))
  (testing "already-idiomatic code is untouched"
    (doseq [src ["(if c a b)" "(when x y)" "(not= a b)" "(defn f [x] x)"
                 "(if x y (do a b))"]]
      (is (= src (normed src)) src))))

(deftest checkpoint-normalizes-the-working-set
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'cp.core
                   (str "(ns cp.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn clean [x] (inc x))\n"))
      (api/checkpoint! sess :label "baseline")           ; everything-so-far boundary
      ;; agent writes working-but-clunky code
      (api/add-form! sess 'cp.core
                     "(defn classify [x] (if (not (neg? x)) (if (pos? x) :pos :zero) :neg))")
      (api/add-form! sess 'cp.core
                     (str "(deftest classify-t\n"
                          "  (is (= :pos (classify 2)))\n"
                          "  (is (= :zero (classify 0)))\n"
                          "  (is (= :neg (classify -2))))"))
      (let [r (api/checkpoint! sess :label "classification done")]
        (testing "changed-since-checkpoint forms are normalized, others untouched"
          (is (= 1 (:normalized r)))
          (is (= ['cp.core/classify] (mapv :form (:rewrites r))))
          (is (re-find #"if-not" (api/query-source sess 'cp.core)))
          (is (re-find #"\(defn clean \[x\] \(inc x\)\)"
                       (api/query-source sess 'cp.core))))
        (testing "behavior verified after normalization (affected tests green)"
          (is (zero? (+ (:fail (:test r)) (:error (:test r)))))
          (is (= [:pos] (api/query-eval sess "(cp.core/classify 5)"))))
        (testing "provenance: a :normalize delta + a :checkpoint boundary"
          (is (contains? (set (map :op (api/query-lineage sess 'cp.core 'classify)))
                         :normalize))
          (is (= :checkpoint (:op (last (store/deltas (:store @sess))))))))
      (testing "an immediate second checkpoint is a no-op"
        (let [r (api/checkpoint! sess)]
          (is (zero? (:normalized r)))))
      (finally (api/close! sess)))))
