(ns slopp.multiproc-test
  "Phase 4 m5b: TWO servers, ONE store dir — the per-agent-server split.
  The journal (m5a) arbitrates commits; sync-with-journal! lets each server
  notice and absorb the other's work (cache refresh + image catch-up +
  trace invalidation)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell]
            [slopp.api :as api]))

(deftest two-servers-one-store
  (let [dir (str (System/getProperty "java.io.tmpdir")
                 "/slopp-m5b-" (System/nanoTime))
        s1  (api/open! {:dir dir})]
    (try
      (api/ingest! s1 'tp.core
                   (str "(ns tp.core (:require [clojure.test :refer [deftest is]]))\n"
                        "(defn f [x] (inc x))\n"
                        "(defn h [x] (dec x))\n"
                        "(deftest f-t (is (= 2 (f 1))))\n"))
      (let [s2 (api/open! {:dir dir})]           ; second server, same dir
        (try
          (testing "server 2 opens onto server 1's work"
            (is (= [2] (api/query-eval s2 "(tp.core/f 1)"))))

          (testing "s1 commits; s2 absorbs it — cache AND image"
            (api/edit-replace! s1 'tp.core 'f "(defn f [x] (+ x 10))"
                               :prompt "s1's change" :agent "server-1")
            (api/edit-replace! s1 'tp.core 'f-t
                               "(deftest f-t (is (= 11 (f 1))))")
            (let [r (api/sync-with-journal! s2)]
              (is (pos? (:synced r 0))))
            (is (= [11] (api/query-eval s2 "(tp.core/f 1)")))
            (is (re-find #"\(\+ x 10\)" (api/query-source s2 'tp.core))))

          (testing "and the other direction"
            (api/add-form! s2 'tp.core "(defn g [x] (* 2 (f x)))"
                           :prompt "s2's addition" :agent "server-2")
            (api/sync-with-journal! s1)
            (is (= [22] (api/query-eval s1 "(tp.core/g 1)"))))

          (testing "a STALE different-form write rebases and lands (no sync needed)"
            (api/edit-replace! s1 'tp.core 'f "(defn f [x] (+ x 100))")
            (api/edit-replace! s1 'tp.core 'f-t
                               "(deftest f-t (is (= 101 (f 1))))")
            ;; s2 has NOT synced; its base is stale, but it touches h only
            (let [r (api/edit-replace! s2 'tp.core 'h "(defn h [x] (- x 5))"
                                       :prompt "stale but different form")]
              (is (nil? (:error r)) (pr-str r))
              (is (nil? (:conflict r))))
            (api/sync-with-journal! s1)
            (is (re-find #"\(- x 5\)" (api/query-source s1 'tp.core)))
            (is (re-find #"\(\+ x 100\)" (api/query-source s1 'tp.core))))

          (testing "a cross-server same-form race surfaces the conflict"
            (api/edit-replace! s1 'tp.core 'h "(defn h [x] :server-1)")
            ;; s2 unsynced: edits the SAME form from a stale base
            (let [r (api/edit-replace! s2 'tp.core 'h "(defn h [x] :server-2)")]
              (is (some? (:conflict r)) (pr-str r)))
            (is (re-find #":server-1" (api/query-source s1 'tp.core))))

          (testing "provenance shows which server did what"
            (api/sync-with-journal! s2)
            (let [hist (pr-str (api/query-history s2))]
              (is (re-find #"server-1" hist))
              (is (re-find #"server-2" hist))))
          (finally (api/close! s2))))
      (finally
        (api/close! s1)
        (clojure.java.shell/sh "rm" "-rf" dir)))))
