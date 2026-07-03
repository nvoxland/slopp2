(ns slopp.history-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]))

(deftest form-history-is-reconstructible
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'h.core "(ns h.core)\n(defn f [x] x)\n(defn g [x] (f x))\n")
      (api/edit-replace! sess 'h.core 'f "(defn f [x] (inc x))" :prompt "bump by one")
      (api/edit-replace! sess 'h.core 'f "(defn f [x] (+ 2 x))" :prompt "bump by two")
      (testing "every content version of the form, oldest first, with intent"
        (let [h (api/query-form-history sess 'h.core 'f)]
          (is (= 3 (count h)))
          (is (= [:ingest :replace :replace] (mapv :op h)))
          (is (re-find #"\[x\] x" (:source (first h))))
          (is (= "bump by one" (:prompt (second h))))
          (is (re-find #"\+ 2 x" (:source (last h))))))
      (testing "the log reads as a filterable story"
        (let [hist (api/query-history sess :contains "bump by one")]
          (is (= 1 (count hist)))
          (is (= :replace (:op (first hist)))))
        (is (<= (count (api/query-history sess :limit 3)) 3)))
      (testing "checkpoint labels appear in the story"
        (api/checkpoint! sess :label "phase one done")
        (is (= :checkpoint
               (:op (first (api/query-history sess :contains "phase one"))))))
      (testing "lineage responses stay lean (no bulk sources)"
        (is (not-any? :sources (api/query-lineage sess 'h.core 'f))))
      (finally (api/close! sess)))))
