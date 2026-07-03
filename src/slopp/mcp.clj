(ns slopp.mcp
  "Minimal MCP transport (JSON-RPC 2.0 over stdio) exposing `slopp.api` as tools.
  The pure `handle` dispatch is the core (fully testable with plain maps);
  `serve!`/`-main` are the thin newline-delimited-JSON stdio loop.

  Tool names use underscores (MCP restricts names to [A-Za-z0-9_-]). This is the
  agent-facing surface — everything is form-addressed (ns/name), never file+line."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [cheshire.core :as json]
            [slopp.api :as api]))

(def ^:private protocol-version "2024-11-05")

(def tools
  [{:name "ingest"
    :description "Ingest source as a namespace and load it into the live image."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :source {:type "string"}}
                  :required ["ns" "source"]}}
   {:name "ns_create"
    :description "Create a brand-new namespace, optionally with require clauses (strings like \"[clojure.string :as str]\")."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :requires {:type "array" :items {:type "string"}}}
                  :required ["ns"]}}
   {:name "ns_add_require"
    :description "Add one require clause (e.g. \"[clojure.string :as str]\") to a namespace's ns form (tracked, hot-reloaded)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :require {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "require"]}}
   {:name "ns_remove_require"
    :description "Remove a library's require spec from a namespace's ns form (tracked, hot-reloaded)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :lib {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "lib"]}}
   {:name "edit_move"
    :description "Move a form to just before another form in its namespace — use when a definition must precede its (already-added) caller."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :before {:type "string"} :prompt {:type "string"}}
                  :required ["ns" "name" "before"]}}
   {:name "query_namespaces"
    :description "List every namespace in the store with its form count (orient here first)."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_outline"
    :description "A namespace's shape at a glance: vars with arities, doc line, !-effect status, test-ness. Far cheaper than query_source."
    :inputSchema {:type "object" :properties {:ns {:type "string"}} :required ["ns"]}}
   {:name "query_source"
    :description "Render a namespace's current source from the store (VFS read)."
    :inputSchema {:type "object" :properties {:ns {:type "string"}} :required ["ns"]}}
   {:name "query_symbol"
    :description "Describe a form: id, name, effectfulness (!), source."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "query_references"
    :description "Who references ns/name."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "query_lineage"
    :description "Provenance chain (deltas: op + prompt) for a form."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "query_eval"
    :description "Read-only eval against the live image (the oracle); never edits code."
    :inputSchema {:type "object" :properties {:code {:type "string"}} :required ["code"]}}
   {:name "query_observe"
    :description "Run driver code while capturing the args and return value of calls to ns/name — 'what actually flows through this function?'"
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :code {:type "string"}
                               :limit {:type "integer"}}
                  :required ["ns" "name" "code"]}}
   {:name "query_macroexpand"
    :description "Show a form's macroexpansion (expand-1 and full)."
    :inputSchema {:type "object" :properties {:code {:type "string"}}
                  :required ["code"]}}
   {:name "edit_replace_form"
    :description "Replace a whole top-level form (tracked delta, hot-reload, verify)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :source {:type "string"} :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name" "source"]}}
   {:name "edit_add_form"
    :description "Add a new top-level form to a namespace (tracked delta, hot-reload, verify)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :source {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "source"]}}
   {:name "edit_delete_form"
    :description "Delete a top-level form from a namespace (tracked delta, ns-unmap, verify)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name"]}}
   {:name "edit_group"
    :description "Apply several form writes as ONE atomic intent: all-or-nothing commit, one verification at the end. Use for multi-form refactors."
    :inputSchema {:type "object"
                  :properties {:steps {:type "array"
                                       :items {:type "object"
                                               :properties {:action {:type "string" :enum ["replace" "add" "delete"]}
                                                            :ns {:type "string"}
                                                            :name {:type "string"}
                                                            :source {:type "string"}}
                                               :required ["action" "ns"]}}
                               :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["steps"]}}
   {:name "edit_rename"
    :description "Rename a form and every reference to it, across namespaces (one coordinated delta; shadow-safe)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :old {:type "string"}
                               :new {:type "string"} :prompt {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "old" "new"]}}
   {:name "checkpoint"
    :description "Mark a unit of work done: deterministically normalize the forms changed since the last checkpoint (tracked :normalize delta, re-verified), and record a boundary in the history."
    :inputSchema {:type "object" :properties {:label {:type "string"}}}}
   {:name "test_run"
    :description "Run a namespace's tests (all, or just those named in `only`) in the live image; record the result."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :only {:type "array" :items {:type "string"}}}
                  :required ["ns"]}}
   {:name "restart"
    :description "Restart the live image (D5 backstop); reload all forms."
    :inputSchema {:type "object" :properties {}}}
   {:name "build"
    :description "Materialize every namespace to real .clj files under dir."
    :inputSchema {:type "object" :properties {:dir {:type "string"}} :required ["dir"]}}])

(defn- text [x]
  {:content [{:type "text" :text (if (string? x) x (pr-str x))}]})

(defn- red? [t]
  (and t (pos? (+ (:fail t 0) (:error t 0)))))

(defn- summarize
  "B1: a green-and-quiet edit result compresses to a terse shape (the Go
  baseline showed slopp's verbose green responses were the token loser).
  Anything noteworthy — :error, red tests, NEW warnings, :untested — or an
  explicit :verbose returns the full map."
  [r verbose?]
  (if (or verbose? (:error r) (:untested r)
          (seq (:warnings r)) (red? (:test r)))
    r
    (let [t (:test r)]
      (cond-> {:ok true}
        (:delta r)   (assoc :delta (get-in r [:delta :id]))
        (:group r)   (assoc :group (:group r))
        (:deltas r)  (assoc :deltas (count (:deltas r)))
        (:renamed r) (assoc :renamed (:renamed r))
        t            (assoc :tests (cond-> {:ran (:test t 0) :pass (:pass t 0)}
                                     (:staleness-detected t) (assoc :staleness-healed true)))
        (:affected r) (assoc :affected (let [a (:affected r)]
                                         (if (= :all a) :all (count a))))
        (:existing-warnings r) (assoc :existing-warnings (:existing-warnings r))))))

(defn- call-tool [session {:keys [name arguments]}]
  (let [a   arguments
        sym (fn [k]
              (if-let [v (get a k)]
                (symbol v)
                (throw (ex-info (str "missing required argument :"
                                     (clojure.core/name k) " for " name)
                                {}))))]
    (case name
      "ingest"            (text (api/ingest! session (sym :ns) (:source a)))
      "ns_create"         (text (api/create-ns! session (sym :ns)
                                                :requires (:requires a)))
      "ns_add_require"    (text (-> (api/add-require! session (sym :ns) (:require a)
                                                      :prompt (:prompt a))
                                    (select-keys [:error :warnings :existing-warnings
                                                  :test :affected :delta])
                                    (summarize (:verbose a))))
      "query_namespaces"  (text (api/query-namespaces session))
      "query_outline"     (text (api/query-outline session (sym :ns)))
      "query_source"      (text (api/query-source session (sym :ns)))
      "query_symbol"      (text (api/query-symbol session (sym :ns) (sym :name)))
      "query_references"  (text (vec (api/query-references session (sym :ns) (sym :name))))
      "query_lineage"     (text (vec (api/query-lineage session (sym :ns) (sym :name))))
      "query_eval"        (text (api/query-eval session (:code a)))
      "query_observe"     (text (api/query-observe session (sym :ns) (sym :name)
                                                   (:code a)
                                                   :limit (or (:limit a) 10)))
      "query_macroexpand" (text (api/query-macroexpand session (:code a)))
      "edit_replace_form" (text (-> (api/edit-replace! session (sym :ns) (sym :name)
                                                       (:source a) :prompt (:prompt a))
                                    (select-keys [:error :warnings :existing-warnings
                                                  :untested :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_add_form"     (text (-> (api/add-form! session (sym :ns) (:source a)
                                                   :prompt (:prompt a))
                                    (select-keys [:error :warnings :existing-warnings
                                                  :untested :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_delete_form"  (text (-> (api/delete-form! session (sym :ns) (sym :name)
                                                      :prompt (:prompt a))
                                    (select-keys [:error :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_group"        (text (-> (api/edit-group!
                                     session
                                     (mapv (fn [s] (cond-> {:action (keyword (:action s))
                                                            :ns (symbol (:ns s))}
                                                     (:name s)   (assoc :name (symbol (:name s)))
                                                     (:source s) (assoc :source (:source s))))
                                           (:steps a))
                                     :prompt (:prompt a))
                                    (select-keys [:error :step :group :warnings :existing-warnings
                                                  :test :affected :deltas])
                                    (summarize (:verbose a))))
      ;; arg forgiveness: every eval run guessed name/to before finding old/new
      "edit_rename"       (let [old (or (:old a) (:name a) (:from a))
                                new (or (:new a) (:to a))]
                            (when-not (and old new)
                              (throw (ex-info "edit_rename needs :old and :new (aliases: :name/:from, :to)" {})))
                            (text (-> (api/rename! session (sym :ns) (symbol old)
                                                   (symbol new) :prompt (:prompt a))
                                      (select-keys [:error :renamed :test :affected :delta])
                                      (summarize (:verbose a)))))
      "ns_remove_require" (text (-> (api/remove-require! session (sym :ns) (sym :lib)
                                                         :prompt (:prompt a))
                                    (select-keys [:error :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_move"         (text (api/move-form! session (sym :ns) (sym :name)
                                                :before (sym :before)
                                                :prompt (:prompt a)))
      "checkpoint"        (text (api/checkpoint! session :label (:label a)))
      "test_run"          (text (api/test-run! session (sym :ns)
                                               :only (some->> (:only a) (mapv symbol))))
      "restart"           (do (api/restart! session) (text "restarted"))
      "build"             (text (str "built at " (api/build! session (:dir a))))
      (throw (ex-info (str "unknown tool: " name ". Available: "
                           (str/join ", " (map :name tools)))
                      {})))))

(defn handle
  "Dispatch a JSON-RPC request map; return a response map, or nil for
  notifications. Tool exceptions become an `isError` result (so the agent sees
  the message); protocol errors become JSON-RPC errors."
  [session {:keys [id method params]}]
  (case method
    "initialize" {:jsonrpc "2.0" :id id
                  :result {:protocolVersion protocol-version
                           :capabilities {:tools {}}
                           :serverInfo {:name "slopp" :version "0.1.0"}}}
    "notifications/initialized" nil
    "tools/list" {:jsonrpc "2.0" :id id :result {:tools tools}}
    "tools/call" {:jsonrpc "2.0" :id id
                  :result (try (call-tool session params)
                               (catch Exception e
                                 (assoc (text (str "error: " (ex-message e)))
                                        :isError true)))}
    "ping" {:jsonrpc "2.0" :id id :result {}}
    (when id
      {:jsonrpc "2.0" :id id
       :error {:code -32601 :message (str "method not found: " method)}})))

(defn serve!
  "Newline-delimited-JSON stdio loop over `in-reader`/`out-writer`."
  [session in-reader out-writer]
  (doseq [line (line-seq in-reader) :when (not (str/blank? line))]
    (when-let [resp (handle session (json/parse-string line true))]
      (.write out-writer (str (json/generate-string resp) "\n"))
      (.flush out-writer)))
  nil)

(defn -main
  "Start the stdio MCP server. An optional `dir` argument makes the session
  durable (store at <dir>/.slopp/store.db); without it the session is
  ephemeral."
  [& [dir]]
  (let [session (api/open! (cond-> {:warm-spare? true}
                             dir (assoc :dir dir)))]
    (try
      (serve! session (io/reader System/in) (io/writer System/out))
      (finally (api/close! session)))))
