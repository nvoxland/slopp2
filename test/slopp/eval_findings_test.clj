(ns slopp.eval-findings-test
  "Fixes for what the symmetric eval surfaced (S-series)."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.api :as api]))

(deftest s1-non-compiling-forms-are-rejected-not-silently-committed
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 's1.core
                   (str "(ns s1.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] x)\n"))
      (testing "an add whose form doesn't compile returns {:error}, nothing committed"
        (let [n (count (store/deltas (:store @sess)))
              r (api/add-form! sess 's1.core "(defn bad [] (undefined-fn 1))")]
          (is (:error r))
          (is (re-find #"compile" (:error r)))
          (is (= n (count (store/deltas (:store @sess)))))
          (is (not (re-find #"bad" (api/query-source sess 's1.core))))))
      (testing "the sonnet case: a test referencing an undefined fn is a loud error, not {:ok :ran 0}"
        (let [r (api/add-form! sess 's1.core "(deftest ghost-t (is (= 1 (ghost 1))))")]
          (is (:error r))))
      (testing "a replace that doesn't compile leaves the old form intact everywhere"
        (let [r (api/edit-replace! sess 's1.core 'f "(defn f [x] (nope x))")]
          (is (:error r))
          (is (re-find #"\(defn f \[x\] x\)" (api/query-source sess 's1.core)))
          (is (= [7] (api/query-eval sess "(s1.core/f 7)")))))
      (testing "a group with a non-compiling step commits nothing and the image stays faithful"
        (let [n (count (store/deltas (:store @sess)))
              r (api/edit-group! sess
                                 [{:action :replace :ns 's1.core :name 'f
                                   :source "(defn f [x] (* 2 x))"}
                                  {:action :add :ns 's1.core
                                   :source "(defn g [] (missing))"}]
                                 :prompt "should fail atomically")]
          (is (:error r))
          (is (= n (count (store/deltas (:store @sess)))))
          ;; first step's compile succeeded in the image before step 2 failed —
          ;; the image must be restored to match the (unchanged) store
          (is (= [7] (api/query-eval sess "(s1.core/f 7)")))))
      (finally (api/close! sess)))))

(deftest s2-forward-refs-rejected-at-write-time-and-move-reorders
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 's2.core "(ns s2.core)\n")
      (testing "S1 makes dependency order self-enforcing: a caller added before
                its callee is rejected on the spot (use (declare x) for mutual
                recursion)"
        (is (:error (api/add-form! sess 's2.core "(defn caller [x] (helper x))"))))
      (api/add-form! sess 's2.core "(defn helper [x] (* 2 x))")
      (api/add-form! sess 's2.core "(defn caller [x] (helper x))")
      (api/add-form! sess 's2.core "(defn util [] :u)")
      (testing "edit_move reorders the store (stylistic/structural ordering)"
        (let [r (api/move-form! sess 's2.core 'util :before 'helper
                                :prompt "group utils first")]
          (is (nil? (:error r)))
          (let [src ^String (api/query-source sess 's2.core)]
            (is (< (.indexOf src "util") (.indexOf src "helper"))))))
      (testing "a FRESH load respects the new order; everything still works"
        (api/restart! sess)
        (is (= [10] (api/query-eval sess "(s2.core/caller 5)")))
        (is (= [:u] (api/query-eval sess "(s2.core/util)"))))
      (testing "lineage records the :move"
        (is (contains? (set (map :op (api/query-lineage sess 's2.core 'util)))
                       :move)))
      (testing "validation"
        (is (:error (api/move-form! sess 's2.core 'nope :before 'caller)))
        (is (:error (api/move-form! sess 's2.core 'helper :before 'nope))))
      (finally (api/close! sess)))))

(deftest remove-require-is-symmetric
  (let [sess (api/open!)]
    (try
      (api/create-ns! sess 'rr.core :requires ["[clojure.string :as str]"
                                               "[clojure.set :as cset]"])
      (let [r (api/remove-require! sess 'rr.core 'clojure.set)]
        (is (nil? (:error r)))
        (is (not (re-find #"clojure\.set" (api/query-source sess 'rr.core))))
        (is (re-find #"clojure\.string" (api/query-source sess 'rr.core))))
      (is (:error (api/remove-require! sess 'rr.core 'clojure.set)))  ; already gone
      (finally (api/close! sess)))))
