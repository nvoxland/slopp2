(ns slopp.merge-test
  "Phase 4 m2: the CRDT merge. Two stores diverge from a common delta-log
  prefix; merge-logs replays theirs' suffix onto ours, form-id-keyed:
  different-form work merges clean, identical changes converge silently,
  same-form divergence = MV conflict (ours kept, theirs surfaced)."
  (:require [clojure.test :refer [deftest is testing]]
            [rewrite-clj.parser :as p]
            [slopp.store :as store]
            [slopp.render :as render]))

(def base-src "(ns m.core)\n(defn a [x] x)\n(defn b [x] x)\n(defn c [x] x)\n")

(defn- base [] (store/ingest (store/empty-store) 'm.core base-src))

(defn- replace! [st nm src]
  (first (store/replace-node st 'm.core nm (p/parse-string src)
                             :prompt (str "edit " nm))))

(deftest different-form-divergence-merges-clean
  (let [b      (base)
        ours   (replace! b 'a "(defn a [x] (+ x 1))")
        theirs (-> b
                   (replace! 'b "(defn b [x] (+ x 2))")
                   (store/append-form 'm.core (p/parse-string "(defn d [x] (* x 4))")
                                      :prompt "new fn" :agent "them")
                   first)
        r      (store/merge-logs ours theirs)]
    (is (empty? (:conflicts r)))
    (is (= 2 (:merged r)))
    (let [src (render/render-ns (:store r) 'm.core)]
      (testing "both sides' work present"
        (is (re-find #"\(\+ x 1\)" src))
        (is (re-find #"\(\+ x 2\)" src))
        (is (re-find #"\(\* x 4\)" src))))
    (testing "provenance survives the merge (their agent, their prompt)"
      (let [merged-add (->> (store/deltas (:store r))
                            (filter #(= :add (:op %))) last)]
        (is (= "them" (:agent merged-add)))))))

(deftest same-form-divergence-is-an-mv-conflict
  (let [b      (base)
        ours   (replace! b 'a "(defn a [x] :ours)")
        theirs (replace! b 'a "(defn a [x] :theirs)")
        r      (store/merge-logs ours theirs)]
    (is (= 1 (count (:conflicts r))))
    (testing "ours kept; theirs carried in the conflict record"
      (is (re-find #":ours" (render/render-ns (:store r) 'm.core)))
      (is (re-find #":theirs" (:theirs (first (:conflicts r)))))
      (is (= 'm.core/a (:form (first (:conflicts r))))))))

(deftest identical-changes-converge-silently
  (let [b      (base)
        ours   (replace! b 'a "(defn a [x] (inc x))")
        theirs (replace! b 'a "(defn a [x] (inc x))")
        r      (store/merge-logs ours theirs)]
    (is (empty? (:conflicts r)))
    (is (zero? (:merged r)))))

(deftest delete-vs-edit-conflicts
  (let [b      (base)
        ours   (first (store/remove-form b 'm.core 'c :prompt "drop c"))
        theirs (replace! b 'c "(defn c [x] :kept-by-them)")
        r      (store/merge-logs ours theirs)]
    (is (= 1 (count (:conflicts r))))
    (is (not (re-find #"kept-by-them" (render/render-ns (:store r) 'm.core))))))

(deftest add-add-id-collisions-are-remapped
  ;; both sides allocate the same next f<n> — the merge must keep BOTH forms
  (let [b      (base)
        ours   (first (store/append-form b 'm.core
                                         (p/parse-string "(defn ours-new [x] x)")
                                         :prompt "ours"))
        theirs (first (store/append-form b 'm.core
                                         (p/parse-string "(defn theirs-new [x] x)")
                                         :prompt "theirs"))
        r      (store/merge-logs ours theirs)]
    (is (empty? (:conflicts r)))
    (let [src (render/render-ns (:store r) 'm.core)]
      (is (re-find #"ours-new" src))
      (is (re-find #"theirs-new" src)))
    (testing "no duplicate form ids after remap"
      (let [ids (map :id (store/forms (:store r) 'm.core))]
        (is (= (count ids) (count (set ids))))))))

(deftest new-namespace-from-theirs-arrives
  (let [b      (base)
        ours   (replace! b 'a "(defn a [x] (+ x 1))")
        theirs (store/ingest b 'm.extra "(ns m.extra)\n(defn e [x] x)\n")
        r      (store/merge-logs ours theirs)]
    (is (empty? (:conflicts r)))
    (is (contains? (set (:new-nses r)) 'm.extra))
    (is (re-find #"defn e" (render/render-ns (:store r) 'm.extra)))))
