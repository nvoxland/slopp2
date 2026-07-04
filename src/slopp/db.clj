(ns slopp.db
  "Durable system of record (C7): SQLite at `<dir>/.slopp/store.db`. Because of
  C1 there are no `.clj` files on disk — this database IS the source code — so
  it gets a real storage engine rather than hand-rolled EDN files.

  Layout:
  - `deltas`   — the append-only log (the history). Op-specific fields live in
                 an EDN `payload` column; EDN stays the value representation,
                 SQLite supplies the durability mechanics.
  - `elements` — the materialized current form-state, kept transactionally
                 in-step with the log (open = read rows, no log replay).
  - `meta`     — the id counter, so a reopened store keeps minting unique ids.

  Every mutation lands in ONE transaction: delta row + its namespace's element
  rows + next-id, atomically. WAL mode for crash safety."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [next.jdbc :as jdbc]
            [rewrite-clj.parser :as p]
            [rewrite-clj.node :as n]))

(defn open!
  "Open (creating if needed) the store db under `dir`; returns the connection."
  ^java.sql.Connection [dir]
  (let [f (io/file dir ".slopp" "store.db")]
    (io/make-parents f)
    (let [conn (jdbc/get-connection
                (jdbc/get-datasource {:dbtype "sqlite" :dbname (str f)}))]
      (jdbc/execute! conn ["PRAGMA journal_mode=WAL"])
      (jdbc/execute! conn ["PRAGMA busy_timeout=5000"])
      (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS meta (
                              k TEXT PRIMARY KEY, v TEXT NOT NULL)"])
      (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS deltas (
                              seq     INTEGER PRIMARY KEY AUTOINCREMENT,
                              id      TEXT UNIQUE NOT NULL,
                              op      TEXT NOT NULL,
                              ns      TEXT NOT NULL,
                              payload TEXT NOT NULL)"])
      (jdbc/execute! conn ["CREATE INDEX IF NOT EXISTS deltas_ns ON deltas(ns)"])
      (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS elements (
                              ns      TEXT NOT NULL,
                              pos     INTEGER NOT NULL,
                              kind    TEXT NOT NULL,
                              form_id TEXT,
                              name    TEXT,
                              source  TEXT NOT NULL,
                              PRIMARY KEY (ns, pos))"])
      conn)))

(defn persist!
  "Write one mutation atomically: the delta, the (full) current element rows of
  the namespaces it touched, and the id counter. Namespaces are small; rewriting
  a ns's rows per edit keeps the write-through trivially correct. Multi-ns
  mutations (e.g. a cross-ns rename) pass the touched `nses` explicitly."
  ([conn store delta] (persist! conn store delta [(:ns delta)]))
  ([conn store delta nses]
   (jdbc/with-transaction [tx conn]
     (jdbc/execute! tx ["INSERT INTO deltas (id, op, ns, payload) VALUES (?,?,?,?)"
                        (:id delta) (name (:op delta)) (str (:ns delta))
                        (pr-str (dissoc delta :id :op :ns))])
     (doseq [ns-sym nses
             :let [elems (get-in store [:namespaces ns-sym :elements])]
             :when elems]
       (jdbc/execute! tx ["DELETE FROM elements WHERE ns = ?" (str ns-sym)])
       (doseq [[pos e] (map-indexed vector elems)]
         (jdbc/execute! tx ["INSERT INTO elements (ns,pos,kind,form_id,name,source)
                             VALUES (?,?,?,?,?,?)"
                            (str ns-sym) pos (name (:kind e)) (:id e)
                            (some-> (:name e) str) (n/string (:node e))])))
     (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('next-id', ?)
                         ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                        (str (:next-id store))]))
   nil))

(defn data-version
  "SQLite's cheap foreign-commit detector: this value changes when ANOTHER
  connection (thread or process) has committed to the database since we last
  looked — our own writes through this connection don't bump it."
  [conn]
  (:data_version (jdbc/execute-one! conn ["PRAGMA data_version"])))

(defn append!
  "Phase-a storage inversion: conditionally append `new-deltas` (+ the full
  element rows of `nses`, + the id counter) in ONE transaction, iff the
  journal head still equals `expected-head` (nil for an empty log). Returns
  true on commit; false if the head moved or the db was busy — the caller
  refreshes its cache and rebases. SQLite (WAL) serializes writers across
  threads AND processes, which is what makes the shared-storage multi-server
  split possible."
  [conn store new-deltas nses expected-head]
  (try
    (jdbc/with-transaction [tx conn]
      (let [head (:deltas/id (jdbc/execute-one!
                              tx ["SELECT id FROM deltas ORDER BY seq DESC LIMIT 1"]))]
        (when (not= head expected-head)
          (throw (ex-info "journal head moved" {::head-moved true})))
        (doseq [d new-deltas]
          (jdbc/execute! tx ["INSERT INTO deltas (id, op, ns, payload) VALUES (?,?,?,?)"
                             (:id d) (name (:op d)) (str (:ns d))
                             (pr-str (dissoc d :id :op :ns))]))
        (doseq [ns-sym nses]
          ;; delete ALWAYS: a ns absent from the store (renamed away) must
          ;; have its rows purged, not linger for the next reopen
          (jdbc/execute! tx ["DELETE FROM elements WHERE ns = ?" (str ns-sym)])
          (doseq [[pos e] (map-indexed vector
                                       (get-in store [:namespaces ns-sym :elements]))]
            (jdbc/execute! tx ["INSERT INTO elements (ns,pos,kind,form_id,name,source)
                                VALUES (?,?,?,?,?,?)"
                               (str ns-sym) pos (name (:kind e)) (:id e)
                               (some-> (:name e) str) (n/string (:node e))])))
        (jdbc/execute! tx ["INSERT INTO meta (k,v) VALUES ('next-id', ?)
                            ON CONFLICT(k) DO UPDATE SET v = excluded.v"
                           (str (:next-id store))])
        true))
    (catch clojure.lang.ExceptionInfo e
      (if (::head-moved (ex-data e)) false (throw e)))
    (catch java.sql.SQLException _ false)))   ; busy/locked = contention

(defn- parse-node
  "Re-parse one element's canonical serialization (its source text) back to its
  CST node. Lossless by rewrite-clj's parse/print round-trip."
  [source]
  (let [nodes (n/children (p/parse-string-all source))]
    (assert (= 1 (count nodes))
            (str "element source did not reparse to one node: " (pr-str source)))
    (first nodes)))

(defn- row->element [row]
  (let [kind (keyword (:elements/kind row))
        node (parse-node (:elements/source row))]
    (if (= :form kind)
      {:id   (:elements/form_id row) :kind :form
       :name (some-> (:elements/name row) symbol) :node node}
      {:kind :sep :node node})))

(defn- row->delta [row]
  (merge {:id (:deltas/id row)
          :op (keyword (:deltas/op row))
          :ns (symbol (:deltas/ns row))}
         (edn/read-string (:deltas/payload row))))

(defn set-line-id!
  "Stamp this store db with its line identity (branch creation)."
  [conn line-id]
  (jdbc/execute! conn ["INSERT INTO meta (k,v) VALUES ('line-id', ?)
                        ON CONFLICT(k) DO UPDATE SET v = excluded.v" line-id]))

(defn commit-shas
  "P4-m8: {delta-id git-sha} from the projection's pinning table (created and
  written by slopp.git; this is read-only convenience for query surfaces).
  Nil when nothing has been projected. Only UNAMBIGUOUS rows: a delta id
  that collides across lines (post-fork id reuse) is omitted, never guessed."
  [conn]
  (when (seq (jdbc/execute! conn ["SELECT name FROM sqlite_master
                                   WHERE type='table' AND name='git_map'"]))
    (into {}
          (keep (fn [row]
                  ;; aggregates come back unqualified; plain columns may not
                  (when (= 1 (or (:n row) (:git_map/n row)))
                    [(or (:delta_id row) (:git_map/delta_id row))
                     (or (:sha row) (:git_map/sha row))])))
          (jdbc/execute! conn ["SELECT delta_id, MIN(sha) AS sha, COUNT(*) AS n
                                FROM git_map GROUP BY delta_id"]))))

(defn deltas-after
  "The journal suffix past the first `n` deltas (incremental sync)."
  [conn n]
  (mapv row->delta
        (jdbc/execute! conn ["SELECT * FROM deltas ORDER BY seq LIMIT -1 OFFSET ?"
                             (long n)])))

(defn load-store
  "Reconstruct the full in-memory store from the db, or nil if empty."
  [conn]
  (when-let [next-id (some-> (jdbc/execute-one!
                              conn ["SELECT v FROM meta WHERE k = 'next-id'"])
                             :meta/v Long/parseLong)]
    {:namespaces (reduce (fn [m row]
                           (update-in m [(symbol (:elements/ns row)) :elements]
                                      (fnil conj []) (row->element row)))
                         {}
                         (jdbc/execute! conn ["SELECT * FROM elements ORDER BY ns, pos"]))
     :deltas     (mapv row->delta
                       (jdbc/execute! conn ["SELECT * FROM deltas ORDER BY seq"]))
     :next-id    next-id
     :line-id    (:meta/v (jdbc/execute-one!
                           conn ["SELECT v FROM meta WHERE k = 'line-id'"]))}))
