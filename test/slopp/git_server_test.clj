(ns slopp.git-server-test
  "P4-m8 M2: the standalone git smart-HTTP server — any git client can clone
  and fetch a slopp store's milestones over http://127.0.0.1:<port>/slopp.git.
  Projection runs before every refs advertisement, so foreign commit points
  are served without a restart."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [slopp.api :as api]
            [slopp.git :as git])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [org.eclipse.jgit.api Git]))

(defn- temp-dir [nm]
  (str (Files/createTempDirectory nm (make-array FileAttribute 0))))

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(def seed
  (str "(ns gs.core (:require [clojure.test :refer [deftest is]]))\n"
       "\n"
       ";; served verbatim over the git protocol\n"
       "(defn f [x] (+ x 10))\n"
       "\n"
       "(deftest f-t (is (= 11 (f 1))))\n"))

(defn- clone-url [port] (str "http://127.0.0.1:" port "/slopp.git"))

(defn- clone! [port to]
  (-> (Git/cloneRepository)
      (.setURI (clone-url port))
      (.setDirectory (io/file to))
      (.call)))

(defn- log-messages [^Git g]
  (mapv #(.getFullMessage %) (-> g (.log) (.call))))

(deftest clone-fetch-and-branches-over-smart-http
  (let [dir  (temp-dir "slopp-git-server")
        sess (api/open! {:dir dir})
        port (free-port)
        srv  (git/start-server! port {:dir dir})]
    (try
      (api/ingest! sess 'gs.core seed)
      (api/commit-point! sess "v1: f ships" :agent "alice")
      (api/edit-replace! sess 'gs.core 'f "(defn f [x] (+ 10 x))"
                         :prompt "flip" :agent "alice")
      (api/commit-point! sess "v2: flipped" :agent "alice")
      (let [clone-dir (temp-dir "slopp-git-clone")]
        (with-open [g (clone! port clone-dir)]
          (testing "the clone IS the store's rendered source, newest milestone"
            (is (= (api/query-source sess 'gs.core)
                   (slurp (io/file clone-dir "src" "gs" "core.clj"))))
            (is (.exists (io/file clone-dir "deps.edn"))))
          (testing "history is the milestone chain, newest first, on main"
            (let [msgs (log-messages g)]
              (is (= 2 (count msgs)))
              (is (str/starts-with? (first msgs) "v2: flipped"))
              (is (str/starts-with? (second msgs) "v1: f ships"))
              (is (= "main" (.getBranch (.getRepository g))))))
          (testing "a milestone made AFTER the clone arrives by fetch"
            (api/edit-replace! sess 'gs.core 'f-t
                               "(deftest f-t (is (= 11 (f 1))) (is true))"
                               :prompt "more coverage" :agent "alice")
            (api/commit-point! sess "v3: coverage" :agent "alice")
            (-> g (.fetch) (.call))
            (let [tip (-> g (.getRepository)
                          (.resolve "refs/remotes/origin/main"))]
              (is (some? tip))
              (with-open [rw (org.eclipse.jgit.revwalk.RevWalk.
                              (.getRepository g))]
                (is (str/starts-with?
                     (.getFullMessage (.parseCommit rw tip))
                     "v3: coverage")))))))
      (testing "branches advertise as refs/heads/<name>"
        (api/branch! sess "feature")
        (api/edit-replace! sess 'gs.core 'f "(defn f [x] (int (+ x 10)))"
                           :prompt "feature work" :agent "bob")
        (api/commit-point! sess "feature: tweak" :agent "bob")
        (let [refs (-> (Git/lsRemoteRepository)
                       (.setRemote (clone-url port))
                       (.setHeads true)
                       (.call))]
          (is (contains? (set (map #(.getName %) refs))
                         "refs/heads/feature"))))
      (finally
        (git/stop-server! srv)
        (api/close! sess)))))

(deftest empty-store-clones-as-empty-repo
  (let [dir  (temp-dir "slopp-git-empty")
        sess (api/open! {:dir dir})
        port (free-port)
        srv  (git/start-server! port {:dir dir})]
    (try
      (api/ingest! sess 'gs.core seed)   ; content but NO milestones yet
      (let [clone-dir (temp-dir "slopp-git-empty-clone")]
        (with-open [g (clone! port clone-dir)]
          (is (nil? (-> g (.getRepository) (.resolve "HEAD"))))))
      (finally
        (git/stop-server! srv)
        (api/close! sess)))))

(deftest real-git-cli-smoke
  ;; the actual `git` binary is the compatibility oracle; skip silently
  ;; when it isn't installed
  (let [git-bin (try (zero? (:exit (sh/sh "git" "--version")))
                     (catch Exception _ false))]
    (when git-bin
      (let [dir  (temp-dir "slopp-git-cli")
            sess (api/open! {:dir dir})
            port (free-port)
            srv  (git/start-server! port {:dir dir})]
        (try
          (api/ingest! sess 'gs.core seed)
          (api/commit-point! sess "v1: f ships" :agent "alice")
          (let [clone-dir (str (temp-dir "slopp-git-cli-clone") "/clone")
                res (sh/sh "git" "clone" (clone-url port) clone-dir)]
            (is (zero? (:exit res)) (:err res))
            (is (= (api/query-source sess 'gs.core)
                   (slurp (io/file clone-dir "src" "gs" "core.clj"))))
            (let [log (sh/sh "git" "-C" clone-dir "log" "--format=%s")]
              (is (str/includes? (:out log) "v1: f ships"))))
          (finally
            (git/stop-server! srv)
            (api/close! sess)))))))
