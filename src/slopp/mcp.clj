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
    :description "The batch write for a BRAND-NEW namespace: land its complete source in one verified call. Cannot overwrite an existing namespace — edit its forms instead."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :source {:type "string"}
                               :agent {:type "string"}}
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
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "require"]}}
   {:name "ns_remove_require"
    :description "Remove a library's require spec from a namespace's ns form (tracked, hot-reloaded)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :lib {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "lib"]}}
   {:name "edit_move"
    :description "Move a form to just before another form in its namespace — use when a definition must precede its (already-added) caller."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :before {:type "string"} :prompt {:type "string"} :agent {:type "string"}}
                  :required ["ns" "name" "before"]}}
   {:name "query_project"
    :description "THE orientation call: every namespace with its full outline (names, arities, doc lines, !-status, test-ness) in one response. Start here."
    :inputSchema {:type "object" :properties {}}}
   {:name "query_search"
    :description "Regex search across all store source; form-addressed hits [{:ns :form :line}]. Search before reading source."
    :inputSchema {:type "object"
                  :properties {:pattern {:type "string"}
                               :limit {:type "integer"}}
                  :required ["pattern"]}}
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
   {:name "query_history"
    :description "The change history, newest first. Default: raw deltas (op, prompt, label; filters ns/contains/limit). Pass collapse=true for EPISODE rows — one per agent-work-unit between checkpoints, the readable long-term view."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :contains {:type "string"}
                               :limit {:type "integer"}
                               :collapse {:type "boolean"}
                               :format {:type "string" :enum ["edn" "text"]}}}}
   {:name "query_form_history"
    :description "Every content version of a form, oldest first, with the prompt that produced it."
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
                               :source {:type "string"} :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name" "source"]}}
   {:name "edit_add_form"
    :description "Add a new top-level form to a namespace (tracked delta, hot-reload, verify)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :source {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "source"]}}
   {:name "edit_delete_form"
    :description "Delete a top-level form from a namespace (tracked delta, ns-unmap, verify)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "name"]}}
   {:name "edit_subform"
    :description "Replace ONE subexpression inside a form (give the exact subform source as `match` and its replacement as `source`) — for small changes inside big forms; never re-transcribe the rest. Wrap = a replacement containing the match."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :form {:type "string"}
                               :match {:type "string"} :source {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "form" "match" "source"]}}
   {:name "edit_revert"
    :description "Revert a form to an earlier version of itself (default: previous; or a specific delta id from query_form_history). Verified and recorded like any write."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :name {:type "string"}
                               :to {:type "string"} :prompt {:type "string"} :agent {:type "string"}
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
                               :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["steps"]}}
   {:name "edit_rename"
    :description "Rename a form and every reference to it, across namespaces (one coordinated delta; shadow-safe)."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :old {:type "string"}
                               :new {:type "string"} :prompt {:type "string"} :agent {:type "string"}
                               :verbose {:type "boolean"}}
                  :required ["ns" "old" "new"]}}
   {:name "edit_extract"
    :description "Extract a unique subform of a function into a new function (free locals become params; placed before the caller; the subform becomes the call). One atomic, verified intent."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"} :from {:type "string"}
                               :form {:type "string"} :name {:type "string"}
                               :prompt {:type "string"} :agent {:type "string"}}
                  :required ["ns" "from" "form" "name"]}}
   {:name "turn_begin"
    :description "Open your TURN: record the user's VERBATIM ask as the root intent of everything you do until turn_end. Required before any write when the server enforces turns. Pass your agent label."
    :inputSchema {:type "object"
                  :properties {:agent {:type "string"} :intent {:type "string"}
                               :user {:type "string"}}
                  :required ["agent" "intent"]}}
   {:name "turn_end"
    :description "Close your turn (stable or not — a red turn is still history). Sub-agents don't call this; they ride your turn."
    :inputSchema {:type "object"
                  :properties {:agent {:type "string"} :note {:type "string"}}
                  :required ["agent"]}}
   {:name "query_changes"
    :description "Net per-form diffs (:was/:now), steps, and the red/green verification arc — for YOUR open episode (pass :agent), or for ANY PAST span: pass :from/:to delta ids straight from a collapsed history row (drill-down)."
    :inputSchema {:type "object"
                  :properties {:agent {:type "string"}
                               :from {:type "string"} :to {:type "string"}}}}
   {:name "episode_revert"
    :description "Scrap your episode: roll every form you changed since your last checkpoint back to that stable spot, as ONE atomic verified group. Forms other agents also touched are skipped and reported in :skipped-shared, never stomped."
    :inputSchema {:type "object"
                  :properties {:agent {:type "string"} :prompt {:type "string"}}}}
   {:name "checkpoint"
    :description "Mark a unit of work done and CLOSE your episode: deterministically normalize the forms YOU changed since your last checkpoint (tracked :normalize delta, re-verified), record a labeled boundary. Pass your :agent label so parallel agents' checkpoints stay independent."
    :inputSchema {:type "object" :properties {:label {:type "string"}
                                              :agent {:type "string"}}}}
   {:name "test_run"
    :description "Run tests in the live image and record the result. No :ns = EVERY namespace's tests in one call (the full-project sweep). :only restricts to named tests; :fresh true restarts first for a guaranteed-faithful run."
    :inputSchema {:type "object"
                  :properties {:ns {:type "string"}
                               :only {:type "array" :items {:type "string"}}
                               :fresh {:type "boolean"}}}}
   {:name "help"
    :description "The slopp workflow cheat-sheet: which tool for what, how to read results."
    :inputSchema {:type "object" :properties {}}}
   {:name "branch_create"
    :description "Create a branch from the current line's state and switch to it (O(1); the image is already correct)."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "branch_switch"
    :description "Checkout another branch (or main): swaps the store and brings the live image in step. The test trace map resets."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "branch_merge"
    :description "Merge a branch into the CURRENT line (switch to main first to merge down). Different-form work lands; same-form divergence returns :conflicts (current line kept, branch surfaced). The branch survives and can continue."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "branch_delete"
    :description "Delete a branch (never the one you are on)."
    :inputSchema {:type "object" :properties {:name {:type "string"}}
                  :required ["name"]}}
   {:name "query_branches"
    :description "List every branch with its head delta, and which one is current."
    :inputSchema {:type "object" :properties {}}}
   {:name "merge_from"
    :description "Merge a diverged COPY of this project (a fork = a copied project dir, edited by its own slopp server) back into this session. Different-form work lands; same-form divergence returns :conflicts (ours kept, theirs surfaced). Absolute dir path."
    :inputSchema {:type "object"
                  :properties {:dir {:type "string"}}
                  :required ["dir"]}}
   {:name "restart"
    :description "Restart the live image (D5 backstop); reload all forms."
    :inputSchema {:type "object" :properties {}}}
   {:name "build"
    :description "Materialize every namespace to real .clj files under dir (absolute path). Optional main (qualified entry fn, e.g. \"calc.core/run-cli\") also emits a GraalVM native-image recipe: a generated launcher plus an executable build-native.sh that compiles a self-contained native binary (optional name overrides the binary name)."
    :inputSchema {:type "object"
                  :properties {:dir {:type "string"} :main {:type "string"}
                               :name {:type "string"}}
                  :required ["dir"]}}])

(def ^:private ^:dynamic *hint*
  "Optional one-line workflow hint, attached to map results (item 3)." nil)

(defn- text [x]
  (let [x (if (and *hint* (map? x)) (assoc x :hint *hint*) x)]
    {:content [{:type "text" :text (if (string? x) x (pr-str x))}]}))

(def ^:private single-write-tools #{"edit_replace_form" "edit_add_form"})

(def ^:private write-tools
  (into single-write-tools
        ["edit_delete_form" "edit_group" "edit_rename" "edit_extract"
         "edit_move" "ns_add_require" "ns_remove_require" "ingest" "ns_create"
         "checkpoint"]))

(defn- track-hint!
  "Session-scoped usage counters → an optional one-line hint (item 3: haiku's
  66-vs-19 call gap was redundant test_runs + scattered single writes)."
  [session tool args]
  (let [s (::stats (swap! session update ::stats
                          (fn [{:keys [test-runs singles last-ns]
                                :or {test-runs 0 singles 0}}]
                            (cond
                              (= tool "test_run")
                              {:test-runs (inc test-runs)
                               :singles singles :last-ns last-ns}

                              (single-write-tools tool)
                              {:test-runs 0
                               :singles (if (= (:ns args) last-ns) (inc singles) 1)
                               :last-ns (:ns args)}

                              (write-tools tool)
                              {:test-runs 0 :singles 0 :last-ns nil}

                              :else
                              {:test-runs test-runs
                               :singles singles :last-ns last-ns}))))]
    (cond
      (>= (:test-runs s) 3)
      "every write already verifies (its result includes :test) — test_run is rarely needed"

      (>= (:singles s) 4)
      "several single-form writes in a row — batch related changes into ONE edit_group"

      :else nil)))

(def ^:private cheat-sheet
  "slopp cheat-sheet
TURN:    turn_begin {agent, intent: <user's verbatim ask>} FIRST -- writes are
         refused without an open turn; turn_end {agent} when done (red is ok)
ORIENT:  query_project (everything, one call) · query_search {pattern} (the grep)
         query_symbol {ns name} (one form's source) · query_references {ns name}
OBSERVE: query_eval {code} (your REPL: call anything; cannot redefine code)
         query_observe {ns name code} (capture args/returns flowing through a fn)
WRITE:   every write verifies immediately and returns :test — trust it.
         edit_add_form / edit_replace_form {ns name source prompt}
         edit_group {steps prompt}  <- SEVERAL forms for one reason: always batch
         edit_rename {ns old new}   <- never rename by editing call sites
         edit_extract {ns from form name} · edit_move {ns name before}
         ingest {ns source}         <- whole NEW namespace in one call
         ns_add_require / ns_remove_require  <- never hand-edit the ns form
RULES:   every write must compile (define callees first; (declare x) for cycles)
         red-first TDD = minimal fn + test in ONE edit_group, then replace
READ RESULTS: {:ok true ...} terse green · :failures = why (expected/actual)
         :diagnosis :genuine = real red, yours · :staleness-detected = healed
         :warnings = fix with edit_rename per :suggest · :untested = add a test
FINISH:  checkpoint {label} (tidies, lints, marks the unit boundary)")

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
        (:image-healed r) (assoc :image-healed true)
        (:existing-warnings r) (assoc :existing-warnings (:existing-warnings r))))))

(defn- call-tool [session {:keys [name arguments]}]
  (api/sync-with-journal! session)      ; m5b: absorb other servers' commits
  (when (and (:require-turns? @session)
             (contains? write-tools name)
             (not= "checkpoint" name))  ; checkpoint closes work; always allowed
    (let [agent (:agent arguments)]
      (cond
        (nil? agent)
        (throw (ex-info (str name " needs an :agent label (turns are enforced "
                             "here — every write must trace to who did it and "
                             "why)")
                        {}))
        (not (api/turn-open? session agent))
        (throw (ex-info (str "no open turn for \"" agent "\" — call "
                             "turn_begin {agent, intent: <the user's verbatim "
                             "ask>} first; sub-agents ride their root agent's "
                             "turn")
                        {})))))
  (let [a   arguments
        sym (fn [k]
              (if-let [v (get a k)]
                (symbol v)
                (throw (ex-info (str "missing required argument :"
                                     (clojure.core/name k) " for " name)
                                {}))))]
    (case name
      "ingest"            (text (api/ingest! session (sym :ns) (:source a) :agent (:agent a)))
      "ns_create"         (text (api/create-ns! session (sym :ns)
                                                :requires (:requires a)))
      "ns_add_require"    (text (-> (api/add-require! session (sym :ns) (:require a)
                                                      :prompt (:prompt a))
                                    (select-keys [:error :warnings :existing-warnings
                                                  :test :affected :delta])
                                    (summarize (:verbose a))))
      "query_project"     (text (api/query-project session))
      "query_search"      (text (api/query-search session (:pattern a)
                                                  :limit (or (:limit a) 30)))
      "query_namespaces"  (text (api/query-namespaces session))
      "query_outline"     (text (api/query-outline session (sym :ns)))
      "query_source"      (text (api/query-source session (sym :ns)))
      "query_symbol"      (text (api/query-symbol session (sym :ns) (sym :name)))
      "query_references"  (text (vec (api/query-references session (sym :ns) (sym :name))))
      "query_lineage"     (text (vec (api/query-lineage session (sym :ns) (sym :name))))
      "turn_begin"        (text (api/turn-begin! session :agent (:agent a)
                                                  :intent (:intent a)
                                                  :user (:user a)))
      "turn_end"          (text (api/turn-end! session :agent (:agent a)
                                               :note (:note a)))
      "query_changes"     (text (api/query-changes session :agent (:agent a)
                                                    :from (:from a) :to (:to a)))
      "episode_revert"    (text (-> (api/revert-episode! session
                                                         :agent (:agent a)
                                                         :prompt (:prompt a))
                                    (select-keys [:error :conflict :reverted
                                                  :skipped-shared :note :test
                                                  :group :affected])
                                    (summarize (:verbose a))))
      "query_history"     (text (api/query-history session
                                                   :ns (some-> (:ns a) symbol)
                                                   :contains (:contains a)
                                                   :collapse (:collapse a)
                                                   :format (:format a)
                                                   :limit (or (:limit a) 20)))
      "query_form_history" (text (api/query-form-history session (sym :ns) (sym :name)))
      "query_eval"        (text (api/query-eval session (:code a)))
      "query_observe"     (text (api/query-observe session (sym :ns) (sym :name)
                                                   (:code a)
                                                   :limit (or (:limit a) 10)))
      "query_macroexpand" (text (api/query-macroexpand session (:code a)))
      "edit_replace_form" (text (-> (api/edit-replace! session (sym :ns) (sym :name)
                                                       (:source a) :prompt (:prompt a)
                                                       :agent (:agent a))
                                    (select-keys [:error :warnings :existing-warnings
                                                  :untested :image-healed :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_add_form"     (text (-> (api/add-form! session (sym :ns) (:source a)
                                                   :prompt (:prompt a)
                                                   :agent (:agent a))
                                    (select-keys [:error :warnings :existing-warnings
                                                  :untested :image-healed :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_delete_form"  (text (-> (api/delete-form! session (sym :ns) (sym :name)
                                                      :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys [:error :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_group"        (text (-> (api/edit-group!
                                     session
                                     (mapv (fn [s]
                                             (let [action (or (:action s) (:op s))] ; :op guessed in evals
                                               (when-not (contains? #{"replace" "add" "delete"} action)
                                                 (throw (ex-info (str "edit_group step needs :action of replace|add|delete (got "
                                                                      (pr-str action) "); keys: :action :ns :name :source")
                                                                 {})))
                                               (cond-> {:action (keyword action)
                                                        :ns (symbol (:ns s))}
                                                 (:name s)   (assoc :name (symbol (:name s)))
                                                 (:source s) (assoc :source (:source s)))))
                                           (:steps a))
                                     :prompt (:prompt a) :agent (:agent a))
                                    (select-keys [:error :step :group :warnings :existing-warnings
                                                  :image-healed :test :affected :deltas])
                                    (summarize (:verbose a))))
      ;; arg forgiveness: every eval run guessed name/to before finding old/new
      "edit_rename"       (let [old (or (:old a) (:name a) (:from a))
                                new (or (:new a) (:to a))]
                            (when-not (and old new)
                              (throw (ex-info "edit_rename needs :old and :new (aliases: :name/:from, :to)" {})))
                            (text (-> (api/rename! session (sym :ns) (symbol old)
                                                   (symbol new) :prompt (:prompt a)
                                                   :agent (:agent a))
                                      (select-keys [:error :renamed :test :affected :delta])
                                      (summarize (:verbose a)))))
      "ns_remove_require" (text (-> (api/remove-require! session (sym :ns) (sym :lib)
                                                         :prompt (:prompt a))
                                    (select-keys [:error :test :affected :delta])
                                    (summarize (:verbose a))))
      "edit_subform"      (let [match (or (:match a) (:from a))
                                src   (or (:source a) (:to a))]
                            (when-not (and match src)
                              (throw (ex-info "edit_subform needs :match (exact subform source) and :source (its replacement)" {})))
                            (text (-> (api/edit-subform! session (sym :ns) (sym :form)
                                                         match src
                                                         :prompt (:prompt a)
                                                         :agent (:agent a))
                                      (select-keys [:error :conflict :warnings :existing-warnings
                                                    :untested :image-healed :test :affected :delta :ms])
                                      (summarize (:verbose a)))))
      "edit_revert"       (text (-> (api/revert-form! session (sym :ns) (sym :name)
                                                      :to (:to a) :prompt (:prompt a)
                                                      :agent (:agent a))
                                    (select-keys [:error :conflict :warnings :test
                                                  :affected :delta :ms])
                                    (summarize (:verbose a))))
      "edit_move"         (text (api/move-form! session (sym :ns) (sym :name)
                                                :before (sym :before)
                                                :prompt (:prompt a)
                                                :agent (:agent a)))
      "edit_extract"      (let [subform (or (:form a) (:source a) (:subform a))]
                            (when-not subform
                              (throw (ex-info "edit_extract needs :form (the exact subform source; aliases :source/:subform accepted)" {})))
                            (text (-> (api/extract! session (sym :ns) (sym :from)
                                                    (sym :name) subform
                                                    :prompt (:prompt a))
                                      (select-keys [:error :extracted :group :test :affected])
                                      (summarize (:verbose a)))))
      "checkpoint"         (text (api/checkpoint! session :label (:label a)
                                                  :agent (:agent a)))
      "test_run"          (text (api/test-run! session
                                               (when (:ns a) (sym :ns))
                                               :only (some->> (:only a) (mapv symbol))
                                               :fresh (:fresh a)))
      "help"              (text cheat-sheet)
      "branch_create"     (text (api/branch! session (:name a)))
      "branch_switch"     (text (api/branch-switch! session (:name a)))
      "branch_merge"      (text (api/branch-merge! session (:name a)))
      "branch_delete"     (text (api/branch-delete! session (:name a)))
      "query_branches"    (text (api/query-branches session))
      "merge_from"        (text (api/merge! session (:dir a)))
      "restart"           (do (api/restart! session) (text "restarted"))
      "build"             (text (api/build! session (:dir a)
                                            :main (some-> (:main a) symbol)
                                            :name (:name a)))
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
                  :result (binding [*hint* (track-hint! session
                                                        (:name params)
                                                        (:arguments params))]
                            (try (call-tool session params)
                                 (catch Exception e
                                   (assoc (text (str "error: " (ex-message e)))
                                          :isError true))))}
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
    (swap! session assoc :require-turns? true)   ; real servers enforce turns
    (try
      (serve! session (io/reader System/in) (io/writer System/out))
      (finally (api/close! session)))))
