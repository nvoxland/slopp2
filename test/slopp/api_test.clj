(ns slopp.api-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(deftest operation-surface
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'demo
                   (str "(ns demo)\n"
                        "(defn add [x y] (+ x y))\n"
                        "(defn tainted [a] (swap! a inc))\n"))
      (testing "query.source renders current source from the store (VFS read)"
        (is (re-find #"defn add" (api/query-source sess 'demo))))
      (testing "query.symbol reports effectfulness (D6)"
        (is (false? (:effectful? (api/query-symbol sess 'demo 'add))))
        (is (true? (:effectful? (api/query-symbol sess 'demo 'tainted)))))
      (testing "query.references finds callers"
        (api/edit-replace! sess 'demo 'add "(defn add [x y] (tainted (atom (+ x y))))"
                           :prompt "call tainted")
        (is (seq (api/query-references sess 'demo 'tainted))))
      (testing "query.eval asks the live image (the oracle)"
        (is (= [7] (api/query-eval sess "(+ 3 4)"))))
      (testing "edit.replace-form updates store + hot-reloads image"
        (let [r (api/edit-replace! sess 'demo 'tainted "(defn tainted [a] a)"
                                   :prompt "defang")]
          (is (nil? (:error r)))
          (is (= [42] (api/query-eval sess "(demo/tainted 42)")))))
      (testing "query.lineage shows provenance (ingest + replaces, with prompts)"
        (let [lin (api/query-lineage sess 'demo 'tainted)]
          (is (contains? (set (map :op lin)) :ingest))
          (is (contains? (set (map :op lin)) :replace))
          (is (some #(= "defang" (:prompt %)) lin))))
      (testing "build materializes .clj on demand (C1/C6 explicit build)"
        (let [dir (str (Files/createTempDirectory "slopp-build"
                                                  (make-array FileAttribute 0)))]
          (api/build! sess dir)
          (is (= (api/query-source sess 'demo) (slurp (str dir "/demo.clj"))))))
      (finally (api/close! sess)))))
