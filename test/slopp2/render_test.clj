(ns slopp2.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [slopp2.store :as store]
            [slopp2.render :as render]))

(def corpus
  ["(ns foo)\n\n(defn add [x y]\n  (+ x y))\n\n;; a comment\n(def z 1)\n"
   "(ns bar\n  (:require [clojure.string :as str]))\n\n(def ^:private secret 42)\n"
   ";; leading comment\n(def a 1)(def b 2)\n\n\n"
   "(defn f [x]  x)"])

(deftest render-is-lossless-round-trip
  (testing "render(ingest(src)) == src, whitespace+comments preserved (C1/C6)"
    (doseq [src corpus]
      (let [s (store/ingest (store/empty-store) 'ns src)]
        (is (= src (render/render-ns s 'ns))
            (str "round-trip failed for: " (pr-str src)))))))
