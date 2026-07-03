(ns slopp.index-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.index :as index]))

(def src
  (str "(ns demo)\n"
       "(defn pure [x] (inc x))\n"
       "(defn tainted [a] (swap! a inc))\n"   ; effectful, mis-named (no !)
       "(defn caller [a] (tainted a))\n"      ; effectful via tainted, mis-named
       "(defn ok! [a] (reset! a 0))\n"))      ; effectful, correctly named

(deftest analyze-and-effects
  (let [an (index/analyze src)]
    (testing "effectful reachability propagates through the call graph (D6)"
      (let [eff (index/effectful-vars an)]
        (is (contains? eff 'demo/tainted))
        (is (contains? eff 'demo/caller))
        (is (contains? eff 'demo/ok!))
        (is (not (contains? eff 'demo/pure)))))
    (testing "`!` name must match computed effectfulness (D6)"
      (let [v (set (map :var (index/effect-violations an)))]
        (is (contains? v 'demo/tainted))   ; effectful but not !-named
        (is (contains? v 'demo/caller))    ; effectful (transitively) but not !-named
        (is (not (contains? v 'demo/ok!))) ; effectful and !-named — ok
        (is (not (contains? v 'demo/pure)))))
    (testing "references finds callers of a var"
      (let [refs (index/references an 'demo 'tainted)]
        (is (= 1 (count refs)))
        (is (= 'caller (:from-var (first refs))))))))

(deftest cross-ns-bang-callees-propagate-effects       ; N1
  (let [an (index/analyze
            (str "(ns w (:require [other.store :as st]))\n"
                 "(defn save-all [xs] (doseq [x xs] (st/put! x)))\n"
                 "(defn pure-view [xs] (map :id xs))\n"))]
    (is (contains? (index/effectful-vars an) 'w/save-all))
    (is (not (contains? (index/effectful-vars an) 'w/pure-view)))
    (is (some #(= 'w/save-all (:var %)) (index/effect-violations an)))))

(deftest deftests-are-exempt-from-bang-rule            ; T1
  (let [an (index/analyze
            (str "(ns d (:require [clojure.test :refer [deftest is]]))\n"
                 "(defn go! [a] (swap! a inc))\n"
                 "(deftest go-test (is (= 1 (go! (atom 0)))))\n"))]
    (testing "a test exercising effectful code is NOT a naming violation"
      (is (not-any? #(= 'd/go-test (:var %)) (index/effect-violations an))))
    (testing "but real violations still surface"
      (let [an2 (index/analyze "(ns d)\n(defn go [a] (swap! a inc))\n")]
        (is (some #(= 'd/go (:var %)) (index/effect-violations an2)))))))
