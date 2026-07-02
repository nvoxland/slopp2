(ns slopp2.edit-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp2.store :as store]
            [slopp2.render :as render]
            [slopp2.repl :as repl]
            [slopp2.image :as image]
            [slopp2.edit :as edit]))

(def src "(ns demo)\n(defn add [x y]\n  (+ x y))\n(def z 1)\n")

(defn- ingest [] (store/ingest (store/empty-store) 'demo src))

(deftest replace-form-happy-path
  (let [s (ingest)
        r (edit/replace-form s 'demo 'add "(defn add [x y] (* x y))"
                             :prompt "make it multiply")]
    (testing "no error; delta recorded with prompt (provenance)"
      (is (nil? (:error r)))
      (is (= :replace (:op (:delta r))))
      (is (= "make it multiply" (:prompt (:delta r)))))
    (testing "rendered source reflects the change; other forms untouched"
      (is (re-find #"\(\* x y\)" (render/render-ns (:store r) 'demo)))
      (is (re-find #"\(def z 1\)" (render/render-ns (:store r) 'demo))))
    (testing "form identity is stable across the edit (C2)"
      (is (= (:id (store/form-named s 'demo 'add))
             (:id (store/form-named (:store r) 'demo 'add)))))))

(deftest replace-form-rejects-non-dialect
  (let [s (ingest)]
    (testing "D4: user macros banned"
      (is (:error (edit/replace-form s 'demo 'add "(defmacro add [x] x)"))))
    (testing "D3: denylisted forms rejected"
      (is (:error (edit/replace-form s 'demo 'add "(defn add [x] (eval x))"))))
    (testing "must be exactly one top-level form"
      (is (:error (edit/replace-form s 'demo 'add "(defn add [x] x) (def oops 1)"))))
    (testing "unknown form name"
      (is (:error (edit/replace-form s 'demo 'nope "(defn nope [] 1)"))))))

(deftest replace-form-flags-effect-violation
  (let [s (ingest)
        r (edit/replace-form s 'demo 'add "(defn add [a] (swap! a inc))")]
    (testing "D6: effectful body under a non-! name -> warning + suggested fix"
      (is (nil? (:error r)))
      (is (some #(= 'demo/add (:var %)) (:warnings r)))
      (is (some #(= "add!" (:suggest %)) (:warnings r))))))

(deftest apply-replace-closes-the-loop
  ;; The whole thesis in one test: red -> edit -> hot-reload -> green + provenance.
  (let [target (str "(ns demo2\n"
                    "  (:require [clojure.test :refer [deftest is]]))\n"
                    "(defn add [x y] (+ x y))\n"
                    "(deftest t (is (= 6 (add 2 3))))\n")  ; expects 6, add gives 5 -> red
        s (store/ingest (store/empty-store) 'demo2 target)
        h (repl/start!)]
    (try
      (image/load-ns! h s 'demo2)
      (testing "initially red (add 2 3 = 5, test expects 6)"
        (is (= 1 (:fail (image/test-run h 'demo2)))))
      (let [r (edit/apply-replace! {:store s :image h} 'demo2 'add
                                   "(defn add [x y] (+ x y 1))" :prompt "off-by-one")]
        (testing "edit hot-reloads + reruns tests -> now green"
          (is (nil? (:error r)))
          (is (= 0 (:fail (:test r))))
          (is (= 1 (:pass (:test r)))))
        (testing "image reflects the redefinition"
          (is (= [6] (repl/eval! h "(demo2/add 2 3)"))))
        (testing "verification recorded as provenance (C4)"
          (is (= :verify (:op (last (store/deltas (:store (:system r)))))))))
      (finally (repl/stop! h)))))
