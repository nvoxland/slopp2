(ns slopp.repl
  "The owned live image (D5): slopp launches and manages a JVM Clojure nREPL as
  a subprocess. `refresh` (hot eval/redefine) is the fast path; `restart!` throws
  the process away for a guaranteed-faithful fresh image — the correctness
  backstop. Phase-1 uses plain restart; the warm-spare optimization is deferred."
  (:require [clojure.java.io :as io]
            [nrepl.core :as nrepl])
  (:import [java.io BufferedReader]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util.concurrent TimeUnit]))

(def ^:private clojure-bin
  "Absolute path avoids PATH surprises under ProcessBuilder (Phase-1; portability
  noted)."
  "/opt/homebrew/bin/clojure")

(defn- default-cmd []
  ;; A clean target image: just Clojure + nREPL. Target forms are eval'd IN over
  ;; the client (no-disk model, C1) — the image needs no source on its classpath.
  [clojure-bin "-Sdeps" "{:deps {nrepl/nrepl {:mvn/version \"1.3.1\"}}}"
   "-M" "-m" "nrepl.cmdline"])

(defn- temp-dir []
  (str (Files/createTempDirectory "slopp-image" (make-array FileAttribute 0))))

(defn- read-port
  "Block reading the subprocess's merged output until it announces its port."
  [^Process proc ^BufferedReader rdr timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (when (> (System/currentTimeMillis) deadline)
        (throw (ex-info "owned image did not report a port in time" {})))
      (if-let [line (.readLine rdr)]
        (if-let [m (re-find #"port (\d+)" line)]
          (Long/parseLong (second m))
          (recur))
        (throw (ex-info "owned image ended before reporting a port" {}))))))

(defn start!
  "Launch a fresh owned image; returns a handle for eval!/restart!/stop!."
  ([] (start! {}))
  ([{:keys [cmd dir timeout-ms] :or {timeout-ms 60000}}]
   (let [cmd (or cmd (default-cmd))
         dir (or dir (temp-dir))
         pb  (doto (ProcessBuilder. ^java.util.List cmd)
               (.redirectErrorStream true)
               (.directory (io/file dir)))
         proc (.start pb)
         rdr  (io/reader (.getInputStream proc))
         port (read-port proc rdr timeout-ms)
         conn (nrepl/connect :port port)
         client (nrepl/client conn 30000)
         session (nrepl/new-session client)]
     {:process proc :port port :conn conn :client client :session session
      :reader rdr :dir dir})))

(defn eval!
  "Eval `code` in the image; returns a vector of returned values, read as data
  when readable and left as the raw printed string otherwise (so evals that
  return unreadable objects — namespaces, functions — don't blow up)."
  [{:keys [client session]} code]
  (->> (nrepl/message client {:op "eval" :code code :session session})
       (keep :value)
       (mapv (fn [v] (try (read-string v) (catch Exception _ v))))))

(defn stop!
  "Destroy the image subprocess and release its connection."
  [{:keys [^java.io.Closeable conn ^Process process]}]
  (when conn (.close conn))
  (when process
    (.destroy process)
    (.waitFor process 5 TimeUnit/SECONDS))
  nil)

(defn restart!
  "Stop the image and start a fresh one (the D5 correctness backstop). Returns a
  new handle; the old one is dead."
  ([handle] (restart! handle {}))
  ([handle opts] (stop! handle) (start! opts)))
