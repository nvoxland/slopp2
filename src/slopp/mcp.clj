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
   {:name "edit_replace_form"
    :description "Replace a whole top-level form (tracked delta, hot-reload, verify)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :source {:type "string"} :prompt {:type "string"}}
                  :required ["ns" "name" "source"]}}
   {:name "edit_add_form"
    :description "Add a new top-level form to a namespace (tracked delta, hot-reload, verify)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :source {:type "string"}
                               :prompt {:type "string"}}
                  :required ["ns" "source"]}}
   {:name "edit_delete_form"
    :description "Delete a top-level form from a namespace (tracked delta, ns-unmap, verify)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :prompt {:type "string"}}
                  :required ["ns" "name"]}}
   {:name "edit_rename"
    :description "Rename a form and every reference to it, across namespaces (one coordinated delta; shadow-safe)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :old {:type "string"}
                               :new {:type "string"} :prompt {:type "string"}}
                  :required ["ns" "old" "new"]}}
   {:name "test_run"
    :description "Run a namespace's tests in the live image; record the result."
    :inputSchema {:type "object" :properties {:ns {:type "string"}} :required ["ns"]}}
   {:name "restart"
    :description "Restart the live image (D5 backstop); reload all forms."
    :inputSchema {:type "object" :properties {}}}
   {:name "build"
    :description "Materialize every namespace to real .clj files under dir."
    :inputSchema {:type "object" :properties {:dir {:type "string"}} :required ["dir"]}}])

(defn- text [x]
  {:content [{:type "text" :text (if (string? x) x (pr-str x))}]})

(defn- call-tool [session {:keys [name arguments]}]
  (let [a   arguments
        sym #(symbol (get a %))]
    (case name
      "ingest"            (do (api/ingest! session (sym :ns) (:source a)) (text "ok"))
      "query_source"      (text (api/query-source session (sym :ns)))
      "query_symbol"      (text (api/query-symbol session (sym :ns) (sym :name)))
      "query_references"  (text (vec (api/query-references session (sym :ns) (sym :name))))
      "query_lineage"     (text (vec (api/query-lineage session (sym :ns) (sym :name))))
      "query_eval"        (text (api/query-eval session (:code a)))
      "edit_replace_form" (text (-> (api/edit-replace! session (sym :ns) (sym :name)
                                                       (:source a) :prompt (:prompt a))
                                    (select-keys [:error :warnings :test :affected :delta])))
      "edit_add_form"     (text (-> (api/add-form! session (sym :ns) (:source a)
                                                   :prompt (:prompt a))
                                    (select-keys [:error :warnings :test :affected :delta])))
      "edit_delete_form"  (text (-> (api/delete-form! session (sym :ns) (sym :name)
                                                      :prompt (:prompt a))
                                    (select-keys [:error :test :affected :delta])))
      "edit_rename"       (text (-> (api/rename! session (sym :ns) (sym :old)
                                                 (sym :new) :prompt (:prompt a))
                                    (select-keys [:error :renamed :test :affected :delta])))
      "test_run"          (text (api/test-run! session (sym :ns)))
      "restart"           (do (api/restart! session) (text "restarted"))
      "build"             (text (str "built at " (api/build! session (:dir a))))
      (throw (ex-info (str "unknown tool: " name) {})))))

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

(defn -main [& _]
  (let [session (api/open!)]
    (try
      (serve! session (io/reader System/in) (io/writer System/out))
      (finally (api/close! session)))))
