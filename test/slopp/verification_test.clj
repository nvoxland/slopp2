(ns slopp.verification-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.repl :as repl]
            [slopp.api :as api]))

(def target
  (str "(ns vdemo\n  (:require [clojure.test :refer [deftest is]]))\n"
       "(defn add [x y] (+ x y))\n"
       "(defn mul [x y] (* x y))\n"
       "(deftest add-t (is (= 5 (add 2 3))))\n"
       "(deftest mul-t (is (= 6 (mul 2 3))))\n"))

(deftest tracing-maps-tests-to-forms
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'vdemo target)
      (let [res (api/test-run! sess 'vdemo)]
        (is (= 2 (:pass res))))
      (testing "each test maps to exactly the forms it exercises (D1 form-granularity)"
        (let [tmap (:test-map @sess)]
          (is (= #{'vdemo/add} (tmap 'vdemo/add-t)))
          (is (= #{'vdemo/mul} (tmap 'vdemo/mul-t)))))
      (finally (api/close! sess)))))

(deftest edit-runs-only-affected-tests
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'vdemo target)
      (api/test-run! sess 'vdemo)                          ; builds the trace map
      (testing "editing mul re-runs only mul-t (add-t is untouched by the edit)"
        (let [r (api/edit-replace! sess 'vdemo 'mul "(defn mul [x y] (* y x))"
                                   :prompt "commute")]
          (is (nil? (:error r)))
          (is (= ['vdemo/mul-t] (:affected r)))
          (is (= 1 (:test (:test r))))
          (is (= 1 (:pass (:test r))))))
      (testing "editing a test itself re-runs exactly that test"
        (let [r (api/edit-replace! sess 'vdemo 'mul-t
                                   "(deftest mul-t (is (= 8 (mul 2 4))))"
                                   :prompt "retarget")]
          (is (= ['vdemo/mul-t] (:affected r)))
          (is (= 1 (:pass (:test r))))))
      (testing "without trace info the whole namespace runs (conservative fallback)"
        (let [sess2 (api/open!)]
          (try
            (api/ingest! sess2 'vdemo target)
            (let [r (api/edit-replace! sess2 'vdemo 'mul "(defn mul [x y] (* y x))")]
              (is (= :all (:affected r)))
              (is (= 2 (:test (:test r)))))
            (finally (api/close! sess2)))))
      (finally (api/close! sess)))))

(deftest red-is-cross-checked-on-a-fresh-image
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'vdemo target)
      (testing "staleness: image drifts behind the store's back -> red heals to green"
        ;; poison the image only (the store is untouched) — the classic stale state
        (repl/eval! (:image @sess) "(in-ns 'vdemo) (def add (fn [x y] 999))")
        (let [res (api/test-run! sess 'vdemo)]
          (is (zero? (+ (:fail res) (:error res))))
          (is (true? (:staleness-detected res)))))
      (testing "genuine bug: red survives the fresh image -> confirmed, not healed"
        (let [r (api/edit-replace! sess 'vdemo 'add "(defn add [x y] (- x y))"
                                   :prompt "break it")]
          (is (= 1 (:fail (:test r))))
          (is (true? (:fresh-confirmed (:test r))))
          (is (nil? (:staleness-detected (:test r))))
          (testing "the WHY is in the result (F1) — not lost to image stdout"
            (let [f (first (:failures (:test r)))]
              (is (= 'vdemo/add-t (:test f)))
              (is (= :fail (:type f)))
              (is (re-find #"\(= 5 \(add 2 3\)\)" (:expected f)))
              (is (= "(not (= 5 -1))" (:actual f)))))))
      (finally (api/close! sess)))))
