(ns slopp.evalseed
  "Seed the eval-round-2 template: a known-green ~16-form tasker app, as (a) a
  slopp store and (b) a conventional files project (via build!). Fresh eval
  agents then get IDENTICAL starting codebases for the modify-and-extend task.
  Run: clojure -M -m slopp.evalseed <template-dir>   (see .context/dogfooding.md)"
  (:require [slopp.api :as api]))

(def model-src
  (str "(ns tasker.model\n  (:require [clojure.test :refer [deftest is]]))\n\n"
       "(defn make-task [id title priority due-day]\n"
       "  {:id id :title title :priority priority :due-day due-day :done? false})\n\n"
       "(defn complete [task] (assoc task :done? true))\n\n"
       "(defn overdue? [task today]\n"
       "  (and (not (:done? task)) (< (:due-day task) today)))\n\n"
       "(defn prioritize [tasks]\n"
       "  (vec (sort-by (juxt (comp {:high 0 :med 1 :low 2} :priority) :due-day) tasks)))\n\n"
       "(deftest model-t\n"
       "  (let [t (make-task 1 \"ship\" :high 10)]\n"
       "    (is (false? (:done? t)))\n"
       "    (is (true? (:done? (complete t))))\n"
       "    (is (= [1 2] (mapv :id (prioritize [(make-task 2 \"docs\" :low 5) t]))))))\n\n"
       "(deftest overdue-t\n"
       "  (is (overdue? (make-task 1 \"x\" :low 5) 6))\n"
       "  (is (not (overdue? (complete (make-task 1 \"x\" :low 5)) 6))))\n"))

(def store-src
  (str "(ns tasker.store\n"
       "  (:require [clojure.test :refer [deftest is]]\n"
       "            [tasker.model :as m]\n"
       "            [clojure.edn :as edn]))\n\n"
       "(def registry (atom {}))\n\n"
       "(defn clear! [] (reset! registry {}))\n\n"
       "(defn add-task! [task] (swap! registry assoc (:id task) task))\n\n"
       "(defn remove-task! [id] (swap! registry dissoc id))\n\n"
       "(defn all-tasks [] (vec (vals @registry)))\n\n"
       "(defn save! [path] (spit path (pr-str @registry)))\n\n"
       "(defn load-tasks [path] (edn/read-string (slurp path)))\n\n"
       "(deftest store-t\n"
       "  (clear!)\n"
       "  (add-task! (m/make-task 1 \"ship\" :high 10))\n"
       "  (add-task! (m/make-task 2 \"docs\" :low 5))\n"
       "  (is (= 2 (count (all-tasks))))\n"
       "  (remove-task! 2)\n"
       "  (is (= [1] (mapv :id (all-tasks)))))\n"))

(def report-src
  (str "(ns tasker.report\n"
       "  (:require [clojure.test :refer [deftest is]]\n"
       "            [tasker.model :as m]\n"
       "            [clojure.string :as str]))\n\n"
       "(defn task-line [task]\n"
       "  (str (if (:done? task) \"[x] \" \"[ ] \") (:title task)\n"
       "       \" (\" (name (:priority task)) \")\"))\n\n"
       "(defn overview [tasks today]\n"
       "  (let [overdue (filter #(m/overdue? % today) tasks)]\n"
       "    (str/join \"\\n\"\n"
       "              (concat [(str (count tasks) \" tasks, \" (count overdue) \" overdue\")]\n"
       "                      (map task-line (m/prioritize tasks))))))\n\n"
       "(deftest report-t\n"
       "  (let [ts [(m/make-task 1 \"ship\" :high 10)\n"
       "            (m/complete (m/make-task 2 \"docs\" :low 5))]]\n"
       "    (is (str/starts-with? (overview ts 12) \"2 tasks, 1 overdue\"))\n"
       "    (is (str/includes? (overview ts 12) \"[x] docs\"))))\n"))

(defn seed!
  "Build the template store under `dir` (a slopp session dir) and the matching
  conventional project under `<dir>-files`. Throws unless everything is green."
  [dir]
  (let [sess (api/open! {:dir dir})]
    (try
      (doseq [[ns-sym src] [['tasker.model model-src]
                            ['tasker.store store-src]
                            ['tasker.report report-src]]]
        (let [r (api/ingest! sess ns-sym src)]
          (when (:error r) (throw (ex-info (str "seed ingest failed: " (:error r)) r)))))
      (doseq [ns-sym '[tasker.model tasker.store tasker.report]]
        (let [r (api/test-run! sess ns-sym)]
          (when-not (zero? (+ (:fail r) (:error r)))
            (throw (ex-info (str "seed not green in " ns-sym) r)))))
      (api/checkpoint! sess :label "seed: tasker v1")
      (api/build! sess (str dir "-files"))
      (finally (api/close! sess)))))

(defn -main [& [dir]]
  (seed! (or dir "eval-templates/tasker"))
  (println "seeded green:" dir "and" (str dir "-files"))
  (shutdown-agents)
  (System/exit 0))
