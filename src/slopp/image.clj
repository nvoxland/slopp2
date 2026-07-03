(ns slopp.image
  "Bridge the store to the owned live image: load a namespace's forms straight
  from the CRDT into the running JVM (no disk, C1), and run its tests there,
  recording the green/red result as provenance (D5/D6, C4)."
  (:require [slopp.render :as render]
            [slopp.repl :as repl]
            [slopp.store :as store]))

(defn load-ns!
  "Evaluate `ns-sym`'s current source (rendered from the store) into the image,
  then mark it in `*loaded-libs*` — store namespaces have no classpath presence
  (C1 no-disk), so without the mark a later `(:require ns-sym)` from another
  store namespace would hit the classpath and fail."
  [handle store ns-sym]
  (let [res (repl/load-checked! handle
                                (render/render-ns store ns-sym)
                                (render/ns-path ns-sym))]
    (repl/eval! handle
                (format "(dosync (commute (deref #'clojure.core/*loaded-libs*) conj '%s))"
                        ns-sym))
    (:err res)))

(defn test-run
  "Run `ns-sym`'s clojure.test tests in the live image; returns the summary map
  ({:test :pass :fail :error :type})."
  [handle ns-sym]
  (first (repl/eval! handle (format "(clojure.test/run-tests '%s)" ns-sym))))

(defn traced-test-run
  "Run `test-ns`'s tests in the image with form-tracing (slopp.rt): the fn
  vars of `test-ns`'s dependency CLOSURE are observed (item 2 — not every
  store namespace), so the result maps each test to the forms it exercised.
  `only` (a coll of plain test names) restricts which tests run.
  Returns {:summary {...} :trace {test-sym #{form-sym ...}}}."
  [handle store test-ns & {:keys [only]}]
  (first (repl/eval! handle
                     (format "(slopp.rt/traced-run '%s '%s '%s)"
                             test-ns
                             (vec (sort (store/ns-closure store test-ns)))
                             (pr-str (some-> only vec))))))
