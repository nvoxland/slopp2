(ns slopp.git
  "P4-m8: the git compatibility layer. Projects the journal's :commit
  milestones into a bare repo at `<dir>/.slopp/git` — one git commit per
  commit point, chained in journal order, refs/heads/<branch> per line.

  Ids: a git commit id IS the hash of its bytes (clients re-hash everything;
  ids cannot be minted), so stability comes from DETERMINISM — each native
  commit is a pure function of its marker delta (:tree snapshot, :agent,
  :at, :description) and its parent. `git_map` (main store.db) additionally
  PINS delta→sha at first projection; imported commits (:git-sha markers)
  keep their pushed identity verbatim and are never re-projected.

  Ordering invariant (crash-safe, no coordination): journal marker → git
  objects (content-addressed, idempotent) → git_map row (INSERT OR IGNORE +
  read-back) → ref update (CAS). Every step is derivable from the previous,
  so `ensure-projected!` repairs any interruption on the next call.

  Durability: native milestones re-derive from the journal (the bare repo is
  a cache) — but once anything is PUSHED in, the pushed objects live only in
  the bare repo, which is then durable state."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [slopp.build :as build]
            [slopp.db :as db]
            [slopp.render :as render])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]
           [java.time Instant ZoneOffset]
           [java.util.zip GZIPInputStream]
           [org.eclipse.jgit.dircache DirCache DirCacheEntry]
           [org.eclipse.jgit.lib CommitBuilder Constants FileMode ObjectId
            PersonIdent Repository]
           [org.eclipse.jgit.storage.file FileRepositoryBuilder]
           [org.eclipse.jgit.transport PacketLineOut
            RefAdvertiser$PacketLineOutRefAdvertiser UploadPack]))

;; ---------------------------------------------------------------------------
;; repo + mapping table

(defn open-repo!
  "Open (creating if needed) the bare projection repo at `<dir>/.slopp/git`,
  HEAD linked to refs/heads/main."
  ^Repository [dir]
  (let [git-dir  (io/file dir ".slopp" "git")
        existed? (.exists (io/file git-dir "HEAD"))
        repo     (-> (FileRepositoryBuilder.)
                     (.setGitDir git-dir)
                     (.setBare)
                     (.build))]
    (when-not existed?
      (.create repo true)
      (-> repo (.updateRef Constants/HEAD) (.link "refs/heads/main")))
    repo))

(defn ensure-map!
  "Create the git_map pinning table (delta↔sha) if absent; returns conn.
  Keyed (delta_id, fingerprint): branch journals share main's prefix by
  VALUE, so a shared marker resolves to one row with no fork-point math;
  colliding post-fork ids disambiguate by fingerprint. `line` is informative."
  [conn]
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS git_map (
                          delta_id    TEXT NOT NULL,
                          fingerprint TEXT NOT NULL,
                          sha         TEXT NOT NULL,
                          line        TEXT,
                          PRIMARY KEY (delta_id, fingerprint))"])
  conn)

(defn open-ctx!
  "The projection context over a slopp store dir: bare repo handle, git_map
  connection (main store.db), and the per-process projection lock."
  [dir]
  {:dir      (str dir)
   :repo     (open-repo! dir)
   :map-conn (ensure-map! (db/open! dir))
   :lock     (Object.)})

(defn close-ctx! [{:keys [^Repository repo ^java.sql.Connection map-conn]}]
  (.close repo)
  (.close map-conn)
  nil)

(defn fingerprint
  "Line-independent identity of a :commit marker: SHA-256 of the canonical
  tuple [id at description target] (NOT the whole map — map print order is
  not canonical across EDN round-trips)."
  [d]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (->> (.digest md (.getBytes (pr-str [(:id d) (:at d) (:description d)
                                         (:target d)])
                                StandardCharsets/UTF_8))
         (map #(format "%02x" %))
         (apply str))))

(defn- lookup-sha [conn delta-id fp]
  (:git_map/sha (jdbc/execute-one!
                 conn ["SELECT sha FROM git_map
                        WHERE delta_id = ? AND fingerprint = ?" delta-id fp])))

(defn- record-sha!
  "Pin delta→sha; first writer wins (determinism makes ties identical for
  native commits — read-back keeps every projector converged regardless)."
  [conn delta-id fp sha line]
  (jdbc/execute! conn ["INSERT OR IGNORE INTO git_map
                          (delta_id, fingerprint, sha, line)
                        VALUES (?,?,?,?)" delta-id fp sha line])
  (lookup-sha conn delta-id fp))

;; ---------------------------------------------------------------------------
;; trees

(defn- commit-paths
  "{path content} for a milestone's tree: every namespace under src/ (same
  layout as build!) plus the generated deps.edn, so a clone is runnable."
  [tree-map]
  (into (sorted-map)
        (cons ["deps.edn" (build/deps-edn false)]
              (map (fn [[ns-sym src]]
                     [(str "src/" (render/ns-path ns-sym)) src])
                   tree-map))))

(defn- backfill-tree
  "Best-effort {ns-sym source} at delta `target-id`, for markers that carry
  no :tree (pre-P4-m8 history, retroactive :target markers): fold the
  journal's content deltas into per-ns ordered form sources. LOSSY by design
  — deltas don't record inter-form trivia (top-level comments/blank lines) —
  but deterministic; once projected, the sha is pinned in git_map anyway."
  [deltas target-id]
  (let [upto  (reduce (fn [acc d]
                        (let [acc (conj acc d)]
                          (if (= target-id (:id d)) (reduced acc) acc)))
                      [] deltas)
        state (reduce
               (fn [{:keys [owner] :as acc} d]
                 (case (:op d)
                   (:ingest :add :replace :rename :normalize)
                   (let [srcs     (:sources d)
                         new-fids (remove owner
                                          (or (:form-ids d)
                                              (some-> (:form-id d) vector)
                                              (keys srcs)))]
                     (-> acc
                         (update :owner into (map #(vector % (:ns d)) new-fids))
                         (update :order update (:ns d) (fnil into []) new-fids)
                         (update :sources merge srcs)))

                   :delete
                   (update acc :sources dissoc (:form-id d))

                   :rename-ns
                   (let [{:keys [old new]} d]
                     (-> acc
                         (update :owner update-vals #(if (= old %) new %))
                         (update :order
                                 (fn [o] (-> o
                                             (assoc new (get o old))
                                             (dissoc old))))
                         (update :sources merge (:sources d))))

                   ;; markers, :move (ordering is cosmetic here), unknown: skip
                   acc))
               {:owner {} :order {} :sources {}}
               upto)]
    (into (sorted-map)
          (keep (fn [[ns-sym fids]]
                  (let [live (filter #(contains? (:sources state) %)
                                     (distinct fids))]
                    (when (seq live)
                      [ns-sym (apply str (map #(str (get (:sources state) %)
                                                    "\n")
                                              live))]))))
          (:order state))))

;; ---------------------------------------------------------------------------
;; commits + refs

(defn- author-email ^String [agent]
  (let [s (str/replace (str agent) #"[^A-Za-z0-9._-]" ".")]
    (str (if (str/blank? s) "slopp" s) "@slopp")))

(defn- commit-message [d]
  (str (:description d)
       "\n\nSlopp-Commit: " (:id d) "\n"
       (when (= :red (:status d)) "Slopp-Status: red\n")))

(defn- insert-commit!
  "Build blobs + tree + commit for marker `d` and return the sha. Pure
  function of (parent-sha, d, tree-map) — determinism is what makes the
  projection rebuildable."
  [^Repository repo parent-sha d tree-map]
  (with-open [ins (.newObjectInserter repo)]
    (let [dc (DirCache/newInCore)
          b  (.builder dc)]
      (doseq [[^String path ^String content] (commit-paths tree-map)]
        (let [blob (.insert ins Constants/OBJ_BLOB
                            (.getBytes content StandardCharsets/UTF_8))]
          (.add b (doto (DirCacheEntry. path)
                    (.setFileMode FileMode/REGULAR_FILE)
                    (.setObjectId blob)))))
      (.finish b)
      (let [tree-id (.writeTree dc ins)
            at      (Instant/ofEpochMilli (long (:at d)))
            who     (str (or (:agent d) "slopp"))
            ;; reflection-free ctors matter: reflective JGit calls resolve
            ;; classes per-thread and break on server dispatch threads
            cb      (doto (CommitBuilder.)
                      (.setTreeId tree-id)
                      (.setAuthor (PersonIdent. who (author-email (:agent d))
                                                at ^java.time.ZoneId ZoneOffset/UTC))
                      (.setCommitter (PersonIdent. "slopp" "slopp@slopp"
                                                   at ^java.time.ZoneId ZoneOffset/UTC))
                      (.setMessage (commit-message d)))]
        (when parent-sha
          (.setParentId cb (ObjectId/fromString parent-sha)))
        (let [cid (.insert ins cb)]
          (.flush ins)
          (.name cid))))))

(defn- set-branch-ref!
  "Point refs/heads/<nm> at `sha` (CAS; the journal is authoritative, so a
  lost race is retried against the moved ref — convergence, not failure)."
  [^Repository repo nm sha]
  (let [ref-name (str "refs/heads/" nm)
        new-id   (ObjectId/fromString sha)]
    (loop [n 0]
      (let [cur (.resolve repo ref-name)]
        (when-not (= cur new-id)
          (let [ru  (doto (.updateRef repo ref-name)
                      (.setExpectedOldObjectId (or cur (ObjectId/zeroId)))
                      (.setNewObjectId new-id)
                      (.setForceUpdate true))
                res (.name (.update ru))]
            (cond
              (#{"NEW" "FORCED" "FAST_FORWARD" "NO_CHANGE"} res) nil
              (and (= "LOCK_FAILURE" res) (< n 3)) (recur (inc n))
              :else (throw (ex-info (str "git ref update failed: " res)
                                    {:ref ref-name :result res})))))))))

;; ---------------------------------------------------------------------------
;; projection

(defn project-journal!
  "Walk one journal's deltas in order, minting a git commit for every
  :commit marker not yet pinned in git_map. Parent = the previous marker's
  sha — journal order IS the chain (a retroactive :target marker therefore
  lands as the NEWEST commit, carrying the older tree; the journal is the
  truth, git mirrors it). Markers carrying :git-sha (imports) only repair
  their mapping row — never re-projected. Returns the tip sha or nil."
  [{:keys [repo map-conn]} line-label deltas]
  (reduce
   (fn [parent d]
     (if (= :commit (:op d))
       (let [fp (fingerprint d)]
         (or (lookup-sha map-conn (:id d) fp)
             (if-let [gs (:git-sha d)]
               (record-sha! map-conn (:id d) fp gs line-label)
               (let [tree (or (:tree d) (backfill-tree deltas (:target d)))
                     sha  (insert-commit! repo parent d tree)]
                 (record-sha! map-conn (:id d) fp sha line-label)))))
       parent))
   nil
   deltas))

(defn- branch-journals
  "[[name dir]] for every on-disk branch that has a store.db — checked
  BEFORE db/open!, which would otherwise create one."
  [dir]
  (let [root (io/file dir ".slopp" "branches")]
    (when (.isDirectory root)
      (for [^java.io.File f (.listFiles root)
            :when (and (.isDirectory f)
                       (.exists (io/file f ".slopp" "store.db")))]
        [(.getName f) (str f)]))))

(defn ensure-projected!
  "Bring the bare repo up to date with the journals — main + every on-disk
  branch — advancing refs/heads/* to each line's newest milestone. Reads the
  dbs directly (always-current, no session needed), deterministic and
  idempotent: safe to call before every refs advertisement.
  Returns {:refs {name sha-or-nil}}."
  [{:keys [dir repo map-conn lock] :as ctx}]
  (locking lock
    (let [refs (into {"main" (project-journal! ctx "main"
                                               (db/deltas-after map-conn 0))}
                     (map (fn [[nm bdir]]
                            [nm (with-open [conn (db/open! bdir)]
                                  (project-journal! ctx nm
                                                    (db/deltas-after conn 0)))]))
                     (branch-journals dir))]
      (doseq [[nm sha] refs :when sha]
        (set-branch-ref! repo nm sha))
      {:refs refs})))

;; ---------------------------------------------------------------------------
;; smart-HTTP server (M2: clone/fetch; M3 adds receive-pack)
;;
;; The protocol endpoints, verbatim from the smart-http spec:
;;   GET  /slopp.git/info/refs?service=git-upload-pack   → refs advertisement
;;   POST /slopp.git/git-upload-pack                     → pack negotiation
;; JGit's UploadPack owns the wire format (setBiDirectionalPipe false =
;; stateless RPC); we only route bytes. v0 protocol — the Git-Protocol:
;; version=2 header is deliberately ignored (spec-legal fallback).

(defn- status! [^HttpExchange ex code]
  (.sendResponseHeaders ex code -1)
  (.close ex))

(defn- q-params [^HttpExchange ex]
  (into {}
        (keep (fn [kv]
                (let [[k v] (str/split kv #"=" 2)]
                  [k (java.net.URLDecoder/decode (str v) "UTF-8")])))
        (some-> (.getQuery (.getRequestURI ex)) (str/split #"&"))))

(defn- request-body ^java.io.InputStream [^HttpExchange ex]
  (cond-> (.getRequestBody ex)
    (= "gzip" (some-> (.getFirst (.getRequestHeaders ex) "Content-Encoding")
                      str/lower-case))
    (GZIPInputStream.)))

(defn- advertise-refs! [ctx ^HttpExchange ex]
  (let [service (get (q-params ex) "service")]
    (if (= "git-upload-pack" service)
      (do (ensure-projected! ctx)
          (doto (.getResponseHeaders ex)
            (.add "Content-Type"
                  "application/x-git-upload-pack-advertisement")
            (.add "Cache-Control" "no-cache"))
          (.sendResponseHeaders ex 200 0)
          (with-open [os (.getResponseBody ex)]
            (let [pck (PacketLineOut. os)]
              (.writeString pck "# service=git-upload-pack\n")
              (.end pck)
              (-> (doto (UploadPack. ^Repository (:repo ctx))
                    (.setBiDirectionalPipe false))
                  (.sendAdvertisedRefs
                   (RefAdvertiser$PacketLineOutRefAdvertiser. pck))))))
      ;; dumb protocol / receive-pack (until M3): refused, per plan
      (status! ex 403))))

(defn- upload-pack! [ctx ^HttpExchange ex]
  (doto (.getResponseHeaders ex)
    (.add "Content-Type" "application/x-git-upload-pack-result")
    (.add "Cache-Control" "no-cache"))
  (.sendResponseHeaders ex 200 0)
  (with-open [in (request-body ex)
              os (.getResponseBody ex)]
    (doto (UploadPack. ^Repository (:repo ctx))
      (.setBiDirectionalPipe false)
      (.upload in os nil))))

(defn- git-handler ^HttpHandler [ctx]
  (reify HttpHandler
    (handle [_ ex]
      (try
        (let [path   (.getPath (.getRequestURI ex))
              method (.getRequestMethod ex)]
          (cond
            (and (= "GET" method) (str/ends-with? path "/info/refs"))
            (advertise-refs! ctx ex)

            (and (= "POST" method) (str/ends-with? path "/git-upload-pack"))
            (upload-pack! ctx ex)

            :else (status! ex 404)))
        (catch Throwable _
          (try (status! ex 500) (catch Throwable _)))
        (finally (.close ex))))))

(defn start-server!
  "Serve the git smart-HTTP protocol for the store at `:dir` on 127.0.0.1
  (localhost-only, like every slopp transport). Clone with
  `git clone http://127.0.0.1:<port>/slopp.git`.
  Returns {:server :ctx :port} for stop-server!."
  [port {:keys [dir]}]
  (when (str/blank? (str dir))
    (throw (ex-info "the git server needs a durable store :dir" {})))
  (let [ctx    (open-ctx! dir)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
    (.createContext server "/slopp.git" (git-handler ctx))
    (.start server)
    {:server server :ctx ctx :port port}))

(defn stop-server! [{:keys [^HttpServer server ctx]}]
  (.stop server 0)
  (close-ctx! ctx)
  nil)

(defn -main [& [port dir]]
  (let [port (Long/parseLong (or port "7457"))
        dir  (or dir (System/getProperty "user.dir"))]
    (start-server! port {:dir dir})
    (println (str "slopp git server: http://127.0.0.1:" port
                  "/slopp.git  (store: " dir ")"))
    @(promise)))
