(ns slopp.orientation-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]))

(deftest outline-and-namespaces                        ; T2
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'o.core
                   (str "(ns o.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn pure-f\n  \"Adds one.\n  Slowly.\"\n  [x] (inc x))\n"
                        "(defn mut! [a] (swap! a inc))\n"
                        "(deftest t (is (= 2 (pure-f 1))))\n"))
      (api/ingest! sess 'o.util "(ns o.util)\n(defn helper [x] x)\n")
      (testing "query-namespaces: what exists, without knowing names up front"
        (is (= [{:ns 'o.core :forms 4} {:ns 'o.util :forms 2}]
               (api/query-namespaces sess))))
      (testing "query-outline: names + arities + !-status + test-ness + doc line"
        (let [o       (api/query-outline sess 'o.core)
              by-name (into {} (map (juxt :name identity)) (:forms o))]
          (is (= [1] (:arities (by-name 'pure-f))))
          (is (= "Adds one." (:doc (by-name 'pure-f))))
          (is (nil? (:effectful? (by-name 'pure-f))))
          (is (true? (:effectful? (by-name 'mut!))))
          (is (true? (:test? (by-name 't))))
          (is (nil? (:effectful? (by-name 't))))))    ; T1: tests aren't flagged
      (finally (api/close! sess)))))
