(ns slopp.rt
  "Runtime support slopp injects into every owned image (see slopp.repl/start!).
  Lives IN the image, next to the code under management.

  `traced-run` is the D1 form-granularity mechanism: run tests while observing —
  via temporary var instrumentation — exactly which store forms each test
  exercises. The observed test→form map is what lets an edit re-verify only the
  forms it touched, replacing @examples' co-location with runtime observation.

  Known sampling limits (accepted, per the design): value-captured references
  (e.g. `(def g (comp f inc))`) bypass the var and aren't observed; multimethods
  and macros are not instrumented."
  (:require [clojure.test :as t]))

(defn- instrumentable? [v]
  (and (bound? v) (fn? @v)
       (let [m (meta v)]
         (and (not (:macro m)) (not (:test m))))))

(defn- qualified [v]
  (let [m (meta v)]
    (symbol (str (ns-name (:ns m))) (str (:name m)))))

(defn traced-run
  "Run `test-ns`'s test vars (all of them, or just those named in `only`),
  recording which fn vars of `target-nses` each test touches. Instrumentation is
  temporary — originals are restored in a finally. Returns
  {:summary {:test .. :pass .. :fail .. :error .. :type :summary}
   :trace   {qualified-test-sym #{qualified-form-sym ...}}}"
  [test-ns target-nses only]
  (let [touched   (atom #{})
        originals (atom {})]
    (doseq [n target-nses
            v (vals (ns-interns n))
            :when (instrumentable? v)]
      (let [orig @v
            qs   (qualified v)]
        (swap! originals assoc v orig)
        (alter-var-root v (fn [_]
                            (fn [& args]
                              (swap! touched conj qs)
                              (apply orig args))))))
    (try
      (let [tvars    (cond->> (filter (comp :test meta) (vals (ns-interns test-ns)))
                       only (filter (comp (set only) :name meta)))
            counters (ref t/*initial-report-counters*)
            trace    (binding [t/*report-counters* counters]
                       (into {}
                             (for [tv (sort-by (comp :line meta) tvars)]
                               (do (reset! touched #{})
                                   (t/test-vars [tv])
                                   [(qualified tv) @touched]))))]
        {:summary (assoc @counters :type :summary)
         :trace   trace})
      (finally
        (doseq [[v orig] @originals]
          (alter-var-root v (constantly orig)))))))
