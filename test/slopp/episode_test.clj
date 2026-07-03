(ns slopp.episode-test
  "Episodes: the automatic work-unit between an agent's checkpoints — derived
  from the journal (no tagging), PER-AGENT so parallel sub-agents don't
  collapse into one braid, with a shared-form guard on revert."
  (:require [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]))

(def seed
  (str "(ns ep.core (:require [clojure.test :refer [deftest is]]))\n"
       "(defn f [x] (inc x))\n"
       "(defn g [x] (dec x))\n"
       "(defn h [x] x)\n"
       "(deftest f-t (is (= 2 (f 1))))\n"))

(deftest solo-episode-lifecycle
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ep.core seed)
      (api/checkpoint! sess :label "baseline")
      ;; a classic TDD arc: red test change, then the fix
      (api/edit-replace! sess 'ep.core 'f-t "(deftest f-t (is (= 11 (f 1))))"
                         :prompt "want +10 behavior")
      (api/edit-replace! sess 'ep.core 'f "(defn f [x] (+ x 10))"
                         :prompt "implement +10")
      (testing "query-changes = my work since my last stable spot"
        (let [c (api/query-changes sess)]
          (is (= 2 (count (:steps c))))
          (is (= #{'ep.core/f 'ep.core/f-t}
                 (set (map :form (:forms c)))))
          (let [f-chg (first (filter #(= 'ep.core/f (:form %)) (:forms c)))]
            (is (= :modified (:status f-chg)))
            (is (re-find #"inc x" (:was f-chg)))
            (is (re-find #"\+ x 10" (:now f-chg))))
          (testing "the red→green arc is visible"
            (is (= [1 0] (mapv :fail (:verification-arc c)))))))
      (testing "checkpoint closes the episode"
        (api/checkpoint! sess :label "plus-ten")
        (is (empty? (:forms (api/query-changes sess)))))
      (testing "collapsed history reads at episode grain"
        (let [rows (api/query-history sess :collapse true)]
          (is (some #(= "plus-ten" (get-in % [:episode :label])) rows))))
      (finally (api/close! sess)))))

(deftest parallel-agents-have-independent-episodes
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ep.core seed)
      (api/checkpoint! sess :label "baseline")
      ;; two "sub-agents" interleave on one session
      (api/edit-replace! sess 'ep.core 'f "(defn f [x] (+ x 1 1))"
                         :prompt "alice's work" :agent "alice")
      (api/edit-replace! sess 'ep.core 'g "(defn g [x] (- x 2))"
                         :prompt "bob's work" :agent "bob")
      (testing "each agent sees only ITS episode"
        (is (= #{'ep.core/f}
               (set (map :form (:forms (api/query-changes sess :agent "alice"))))))
        (is (= #{'ep.core/g}
               (set (map :form (:forms (api/query-changes sess :agent "bob")))))))
      (testing "alice checkpointing does NOT close bob's episode"
        (api/checkpoint! sess :label "alice done" :agent "alice")
        (is (empty? (:forms (api/query-changes sess :agent "alice"))))
        (is (= #{'ep.core/g}
               (set (map :form (:forms (api/query-changes sess :agent "bob")))))))
      (finally (api/close! sess)))))

(deftest episode-revert-scraps-only-my-unshared-work
  (let [sess (api/open!)]
    (try
      (api/ingest! sess 'ep.core seed)
      (api/checkpoint! sess :label "baseline")
      ;; alice: modifies f, adds a helper — and touches the SHARED form h
      (api/edit-replace! sess 'ep.core 'f "(defn f [x] (* x 9))"
                         :prompt "alice attempt" :agent "alice")
      (api/edit-replace! sess 'ep.core 'f-t "(deftest f-t (is (= 9 (f 1))))"
                         :agent "alice")
      (api/add-form! sess 'ep.core "(defn alice-helper [x] x)" :agent "alice")
      (api/edit-replace! sess 'ep.core 'h "(defn h [x] :alice-touched)"
                         :agent "alice")
      ;; bob also touches h (the shared-form hazard)
      (api/edit-replace! sess 'ep.core 'h "(defn h [x] :bob-touched)"
                         :agent "bob")
      (let [r (api/revert-episode! sess :agent "alice")]
        (testing "alice's exclusive work is rolled back to the boundary"
          (is (nil? (:error r)) (pr-str r))
          (is (= [2] (api/query-eval sess "(ep.core/f 1)")))
          (is (not (re-find #"alice-helper" (api/query-source sess 'ep.core)))))
        (testing "the SHARED form is skipped and reported, not stomped"
          (is (= ['ep.core/h] (:skipped-shared r)))
          (is (re-find #":bob-touched" (api/query-source sess 'ep.core))))
        (testing "the revert is itself provenance, and tests are green again"
          (is (zero? (+ (:fail (:test r)) (:error (:test r)))))))
      (finally (api/close! sess)))))
