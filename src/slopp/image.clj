(ns slopp.image
  "Bridge the store to the owned live image: load a namespace's forms straight
  from the CRDT into the running JVM (no disk, C1), and run its tests there,
  recording the green/red result as provenance (D5/D6, C4)."
  (:require [slopp.render :as render]
            [slopp.repl :as repl]))

(defn load-ns!
  "Evaluate `ns-sym`'s current source (rendered from the store) into the image."
  [handle store ns-sym]
  (repl/eval! handle (render/render-ns store ns-sym))
  nil)

(defn test-run
  "Run `ns-sym`'s clojure.test tests in the live image; returns the summary map
  ({:test :pass :fail :error :type})."
  [handle ns-sym]
  (first (repl/eval! handle (format "(clojure.test/run-tests '%s)" ns-sym))))
