(ns slopp.ergonomics-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.store :as store]
            [slopp.edit :as edit]
            [slopp.api :as api]))

(deftest unparseable-source-returns-error-not-throw   ; F3
  (testing "pure gate"
    (let [r (edit/parse-form "(defn broken [x")]
      (is (:error r))
      (is (re-find #"unparseable" (:error r)))))
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'er.core "(ns er.core)\n(defn f [x] x)\n")
      (testing "add with unbalanced source: {:error}, nothing committed"
        (let [n (count (store/deltas (:store @sess)))
              r (api/add-form! sess 'er.core "(defn broken [x")]
          (is (:error r))
          (is (= n (count (store/deltas (:store @sess)))))))
      (testing "replace and ingest too"
        (is (:error (api/edit-replace! sess 'er.core 'f "(defn f [x")))
        (is (:error (api/ingest! sess 'er2.core "(ns er2.core"))))
      (testing "ingest returns a tidy map now, not the session atom (F8)"
        (let [r (api/ingest! sess 'er3.core "(ns er3.core)\n(def a 1)\n")]
          (is (= 'er3.core (:ns r)))
          (is (= 2 (:forms r)))))
      (finally (api/close! sess)))))

(deftest create-ns-and-add-require                     ; F4 + F5
  (let [sess (api/open!)]
    (try
      (testing "create a namespace directly, with requires"
        (let [r (api/create-ns! sess 'fresh.core
                                :requires ["[clojure.test :refer [deftest is]]"])]
          (is (nil? (:error r)))
          (is (re-find #"\(ns fresh\.core" (api/query-source sess 'fresh.core)))
          (is (re-find #"clojure\.test" (api/query-source sess 'fresh.core)))))
      (testing "duplicate namespace rejected"
        (is (:error (api/create-ns! sess 'fresh.core))))
      (testing "add-require structurally extends the ns form and hot-reloads"
        (let [r (api/add-require! sess 'fresh.core "[clojure.string :as str]")]
          (is (nil? (:error r)))
          (is (re-find #"clojure\.string :as str" (api/query-source sess 'fresh.core)))
          ;; the alias is genuinely live in the image
          (api/add-form! sess 'fresh.core "(defn shout [s] (str/upper-case s))")
          (is (= ["HI"] (api/query-eval sess "(fresh.core/shout \"hi\")")))))
      (testing "duplicate require rejected"
        (is (:error (api/add-require! sess 'fresh.core "[clojure.string :as up]"))))
      (testing "ns without a :require clause gains one"
        (api/create-ns! sess 'bare.core)
        (let [r (api/add-require! sess 'bare.core "[clojure.set :as cset]")]
          (is (nil? (:error r)))
          (api/add-form! sess 'bare.core "(defn u [a b] (cset/union a b))")
          (is (= [#{1 2}] (api/query-eval sess "(bare.core/u #{1} #{2})")))))
      (finally (api/close! sess)))))
