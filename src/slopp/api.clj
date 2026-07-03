(ns slopp.api
  "The agent-facing operation surface (the tools an MCP adapter exposes). A
  session is an atom holding the evolving store + the owned image. Everything is
  form-addressed (ns/name), never file+line: the agent *sees* code via
  `query-source` (the VFS) and *edits* only through `edit-replace!` (tracked
  deltas). `query-eval` lets it observe the live image (the oracle) without
  mutating code.

  With `{:dir ...}` the session is durable (C7): the store is write-through to
  SQLite at `<dir>/.slopp/store.db`, and `open!` reconstructs both the store and
  the live image from it. Without `:dir` the session is ephemeral (tests,
  scratch)."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [slopp.store :as store]
            [slopp.render :as render]
            [slopp.index :as index]
            [slopp.repl :as repl]
            [slopp.image :as image]
            [slopp.edit :as edit]
            [slopp.refactor :as refactor]
            [slopp.normalize :as normalize]
            [slopp.db :as db]))

(declare run-verification!)

(defn- start-spare!
  "Kick off a background-warming spare image (D5 warm spare) if enabled."
  [session]
  (when (:warm-spare? @session)
    (swap! session assoc :spare (future (repl/start!)))))

(defn open!
  "Start a session: the owned image + the store — loaded from `<dir>/.slopp/`
  when `:dir` is given and it has history, empty otherwise. `:warm-spare? true`
  keeps a spare image warming in the background so restarts are near-instant."
  ([] (open! {}))
  ([{:keys [dir warm-spare?]}]
   (let [conn    (when dir (db/open! dir))
         store   (or (some-> conn db/load-store) (store/empty-store))
         image   (repl/start!)
         session (atom {:store store :image image :db conn
                        :warm-spare? (boolean warm-spare?)})]
     (start-spare! session)
     (doseq [ns-sym (store/ns-dependency-order store)]     ; X3: deps first
       (when-let [err (image/load-ns! image store ns-sym)]
         (throw (ex-info (str "image load failed for " ns-sym ": " err) {}))))
     session)))

(defn close! [session]
  (repl/stop! (:image @session))
  (when-let [spare (:spare @session)]
    (repl/stop! @spare))                          ; reap even if still booting
  (when-let [^java.sql.Connection conn (:db @session)]
    (.close conn))
  nil)

(defn- persist-last!
  "Write-through (C7): land the store's newest delta (plus its namespace's
  current elements) in the db, atomically. No-op for ephemeral sessions."
  [session]
  (let [{:keys [db store]} @session]
    (when db
      (db/persist! db store (last (store/deltas store))))))

(defn ingest!
  "The batch write for BRAND-NEW namespaces (W1, user decision): land a whole
  namespace's source in one call. Compile-gated like every write (the image
  loads it FIRST; a failed load commits nothing — T4), then verified and
  recorded like every write. Overwriting an existing namespace is NOT allowed
  — edit its forms instead. Returns {:ns :forms :test} or {:error msg}."
  [session ns-sym source]
  (if (get-in (:store @session) [:namespaces ns-sym])
    {:error (str ns-sym " already exists — edit its forms instead"
                 " (whole-namespace overwrite is not allowed)")}
    (try
      (let [candidate (store/ingest (:store @session) ns-sym source)
            res (repl/load-checked! (:image @session)
                                    (render/render-ns candidate ns-sym)
                                    (render/ns-path ns-sym))]
        (if (:err res)
          {:error (str "namespace failed to load: " (:err res))}
          (do (swap! session assoc :store candidate)
              (persist-last! session)
              (repl/eval! (:image @session)
                          (format "(dosync (commute (deref #'clojure.core/*loaded-libs*) conj '%s))"
                                  ns-sym))
              (let [summary (run-verification! session ns-sym nil)]
                (swap! session update :store store/record-verification ns-sym summary)
                (persist-last! session)
                {:ns ns-sym
                 :forms (count (store/forms candidate ns-sym))
                 :test summary}))))
      (catch Exception e
        {:error (str "unparseable source (unbalanced?): " (ex-message e))}))))

(defn create-ns!
  "F4: create a brand-new namespace, optionally with `:requires` (clause
  strings like \"[clojure.string :as str]\")."
  [session ns-sym & {:keys [requires]}]
  (if (get-in (:store @session) [:namespaces ns-sym])
    {:error (str ns-sym " already exists")}
    (ingest! session ns-sym
             (str "(ns " ns-sym
                  (when (seq requires)
                    (str "\n  (:require " (str/join "\n            " requires) ")"))
                  ")\n"))))

;; --- query.* (read) ---

(defn query-source
  "Render `ns-sym`'s current source from the store (the VFS read)."
  [session ns-sym]
  (render/render-ns (:store @session) ns-sym))

(defn query-symbol
  "Describe the form defining `nm`: id, name, effectfulness (D6), source."
  [session ns-sym nm]
  (let [st  (:store @session)
        f   (store/form-named st ns-sym nm)
        eff (index/effectful-vars (index/analyze (render/render-ns st ns-sym)))]
    (when f
      {:id         (:id f)
       :name       (:name f)
       :effectful? (contains? eff (symbol (str ns-sym) (str nm)))
       :source     (n/string (:node f))})))

(defn query-references
  "Usages of `ns-sym/nm` — who references it."
  [session ns-sym nm]
  (index/references (index/analyze (render/render-ns (:store @session) ns-sym))
                    ns-sym nm))

(defn query-lineage
  "Provenance chain for `nm`: the deltas that created or changed its form (who
  touched it, via which op, driven by which prompt)."
  [session ns-sym nm]
  (let [st (:store @session)
        id (:id (store/form-named st ns-sym nm))]
    (when id
      (->> (store/deltas st)
           (filter (fn [d]
                     (or (= id (:form-id d))
                         (some #{id} (:form-ids d)))))
           ;; lean: bulk content lives in query-form-history, not here
           (mapv #(dissoc % :sources :changeset :result))))))

(defn query-form-history
  "Every content version of `nm`'s form, oldest first, with the intent that
  produced it: [{:delta :op :prompt :source}]. The semantic×history core."
  [session ns-sym nm]
  (let [st (:store @session)
        id (:id (store/form-named st ns-sym nm))]
    (when id
      (vec (for [d     (store/deltas st)
                 :let  [src (get-in d [:sources id])]
                 :when src]
             {:delta (:id d) :op (:op d) :prompt (:prompt d) :source src})))))

(defn query-history
  "The delta log as a story, newest first. Filters: `:ns`, `:contains`
  (substring of prompt/label), `:limit` (default 20)."
  [session & {:keys [ns contains limit] :or {limit 20}}]
  (->> (store/deltas (:store @session))
       reverse
       (filter #(or (nil? ns) (= ns (:ns %))))
       (filter #(or (nil? contains)
                    (some (fn [s] (and s (clojure.string/includes? (str s) contains)))
                          [(:prompt %) (:label %)])))
       (take limit)
       (mapv #(select-keys % [:id :op :ns :prompt :label :group
                              :form-id :form-ids :old :new :before]))))

(defn query-eval
  "Observe-only eval against the live image (the oracle): call anything —
  including effectful fns — but (re)defining code is rejected (T5); writes go
  through the edit tools so provenance stays airtight."
  [session code]
  (if-let [err (edit/observe-gate code)]
    {:error err}
    (repl/eval! (:image @session) code)))

(defn query-observe
  "Run `driver-code` (observe-gated) while capturing the args and return value
  of up to `:limit` calls to `ns-sym/nm` — the oracle's direct answer to 'what
  flows through this function?' (D2: observe, don't declare)."
  [session ns-sym nm driver-code & {:keys [limit] :or {limit 10}}]
  (if-let [err (edit/observe-gate driver-code)]
    {:error err}
    (first (repl/eval! (:image @session)
                       (format "(slopp.rt/observe '%s/%s (fn [] %s) %d)"
                               ns-sym nm driver-code limit)))))

(defn query-macroexpand
  "Expand a form (built-in macros are part of the dialect; expansion is how
  the oracle explains them). Returns {:expand-1 str :full str} or {:error}."
  [session code]
  (try
    (let [{:keys [error]} (edit/parse-form code)]
      ;; parse-form also dialect-checks; for expansion we only care that it READS
      (if (and error (re-find #"unparseable" error))
        {:error error}
        {:expand-1 (first (repl/eval! (:image @session)
                                      (format "(pr-str (macroexpand-1 '%s))" code)))
         :full     (first (repl/eval! (:image @session)
                                      (format "(pr-str (macroexpand '%s))" code)))}))
    (catch Exception e {:error (ex-message e)})))

(defn query-namespaces
  "What exists? Every store namespace with its form count (orientation, T2)."
  [session]
  (let [st (:store @session)]
    (vec (for [ns-sym (keys (:namespaces st))]
           {:ns ns-sym :forms (count (store/forms st ns-sym))}))))

(defn query-outline
  "A namespace's shape at a glance (orientation, T2): every defined var with
  arities, docstring first line, `!`-effect status, and test-ness — a fraction
  of the tokens of reading the source."
  [session ns-sym]
  (let [st  (:store @session)
        an  (index/analyze (render/render-ns st ns-sym))
        eff (index/effectful-vars an)]
    {:ns ns-sym
     :forms
     (vec (for [d (:var-definitions an)
                :when (= ns-sym (:ns d))]
            (cond-> {:name (:name d)}
              (:fixed-arities d)      (assoc :arities (vec (sort (:fixed-arities d))))
              (:varargs-min-arity d)  (assoc :varargs-min (:varargs-min-arity d))
              (:doc d)                (assoc :doc (first (str/split-lines (:doc d))))
              (index/test-definition? d) (assoc :test? true)
              (and (not (index/test-definition? d))
                   (contains? eff (symbol (str ns-sym) (str (:name d)))))
              (assoc :effectful? true))))}))

;; --- verification (D1 tracing + D5 restart-as-diagnostic) ---

(defn- green? [summary]
  (zero? (+ (:fail summary 0) (:error summary 0))))

(defn- fresh-image!
  "Replace the image with a fresh process reloaded from the store — faithful by
  construction (the D5 backstop). With a warm spare, the swap avoids a JVM boot
  on the critical path; the next spare starts warming immediately."
  [session]
  (let [{:keys [image spare]} @session
        fresh (if spare @spare (repl/start!))]    ; deref: ready or nearly so
    (repl/stop! image)
    (swap! session assoc :image fresh :spare nil)
    (start-spare! session)
    (let [{:keys [store image]} @session]
      (doseq [ns-sym (store/ns-dependency-order store)]    ; X3: deps first
        (when-let [err (image/load-ns! image store ns-sym)]
          (throw (ex-info (str "restart load failed for " ns-sym ": " err) {})))))))

(defn restart!
  "D5 escape hatch: the agent-callable fresh-image restart."
  [session]
  (fresh-image! session)
  session)

(defn- hot-load-all!
  "Checked-load `form-ids` from a CANDIDATE store value into the image (S1).
  nil on success. On a compile failure, earlier loads may have landed — restore
  a faithful image from the current (uncommitted) session store and return the
  error message."
  [session candidate form-ids]
  (loop [ids (seq form-ids)]
    (when ids
      (if-let [err (edit/hot-load-form! (:image @session) candidate (first ids))]
        (do (fresh-image! session) err)
        (recur (next ids))))))

(defn- traced-run!
  "Run `test-ns`'s tests (all, or `only` names) with form-tracing; absorb the
  observed test→form map into the session; return the summary."
  [session test-ns only]
  (let [{:keys [image store]} @session
        {:keys [summary trace]} (image/traced-test-run image store test-ns :only only)]
    (swap! session update :test-map merge trace)
    summary))

(defn- diagnosed-run!
  "Run tests; on red, cross-check on a fresh image before believing it (D5
  restart-as-diagnostic — the oracle must not return a false verdict).
  red→green ⇒ the red was image staleness: healed, flagged.
  red→red   ⇒ a real failure, confirmed against a faithful image."
  [session test-ns only]
  (let [r1 (traced-run! session test-ns only)]
    (if (green? r1)
      r1
      (do (fresh-image! session)
          (let [r2 (traced-run! session test-ns only)]
            (if (green? r2)
              (assoc r2 :staleness-detected true)
              (assoc r2 :fresh-confirmed true)))))))

(defn- affected-tests
  "Which tests must re-run after editing `ns-sym/nm`: the tests observed (via
  tracing) to exercise that form — or the form itself if it IS a test. nil =
  no trace information; run everything (conservative)."
  [session ns-sym nm]
  (let [qform (symbol (str ns-sym) (str nm))
        tmap  (:test-map @session)]
    (if (contains? tmap qform)
      [qform]
      (let [hits (->> tmap
                      (keep (fn [[t forms]] (when (contains? forms qform) t)))
                      sort vec)]
        (when (seq hits) hits)))))

(defn- run-verification!
  "Diagnosed run of `affected` tests (grouped by their namespace), or of all of
  `default-ns`'s tests when there's no trace information."
  [session default-ns affected]
  (if (nil? affected)
    (diagnosed-run! session default-ns nil)
    (reduce (fn [acc [tns tsyms]]
              (merge-with (fn [a b]
                            (cond (number? a) (+ a b)
                                  (and (sequential? a) (sequential? b)) (into (vec a) b)
                                  :else (or b a)))
                          acc
                          (diagnosed-run! session tns (mapv (comp symbol name) tsyms))))
            {}
            (group-by (comp symbol namespace) affected))))

;; --- edit.* / runtime ---

(defn edit-replace!
  "Replace the form `nm` in `ns-sym` with `new-source` (O1 whole-form replace):
  pipeline + hot-reload, then re-verify — only the tests the trace map says
  exercise this form (D1), cross-checked on a fresh image if red (D5) — and
  record the outcome as provenance (C4)."
  [session ns-sym nm new-source & {:keys [prompt]}]
  (let [pre-warned (set (map :var (edit/ns-warnings (:store @session) ns-sym)))
        r (edit/apply-replace! @session ns-sym nm new-source :prompt prompt)]
    (if (:error r)
      r
      (let [_        (reset! session (:system r))
            _        (persist-last! session)          ; the :replace delta
            affected (affected-tests session ns-sym nm)
            untested (and (nil? affected) (seq (:test-map @session)))
            summary  (run-verification! session ns-sym affected)
            existing (count (filter (comp pre-warned :var) (:warnings r)))]
        (swap! session update :store store/record-verification ns-sym summary)
        (persist-last! session)                       ; the :verify delta
        (cond-> {:delta    (:delta r)
                 ;; T3: report only NEW violations; pre-existing ones as a count
                 :warnings (vec (remove (comp pre-warned :var) (:warnings r)))
                 :test     summary
                 :affected (or affected :all)}
          (pos? existing) (assoc :existing-warnings existing)
          untested        (assoc :untested true))))))

(defn add-form!
  "Add a new top-level form to `ns-sym` (O1 base write): dialect gate, `:add`
  delta, hot-reload into the image, verification, provenance. Returns
  {:delta :warnings :test :affected} or {:error msg}."
  [session ns-sym source & {:keys [prompt]}]
  (let [{:keys [node error]} (edit/parse-form source)
        nm (some-> node store/form-symbol)]
    (cond
      error {:error error}

      (and nm (store/form-named (:store @session) ns-sym nm))
      {:error (str nm " already exists in " ns-sym)}

      :else
      (let [pre-warned (set (map :var (edit/ns-warnings (:store @session) ns-sym)))]
        (if-let [[st' delta] (store/append-form (:store @session) ns-sym node
                                                :prompt prompt)]
          (if-let [err (hot-load-all! session st' [(:form-id delta)])]
            {:error (str "form failed to compile: " err)}
            (do (swap! session assoc :store st')
                (persist-last! session)
                (let [affected (when nm (affected-tests session ns-sym nm))
                      summary  (run-verification! session ns-sym affected)
                      all-w    (edit/ns-warnings (:store @session) ns-sym)
                      existing (count (filter (comp pre-warned :var) all-w))]
                  (swap! session update :store store/record-verification ns-sym summary)
                  (persist-last! session)
                  (cond-> {:delta    delta
                         ;; T3: only NEW violations; pre-existing ones as a count
                           :warnings (vec (remove (comp pre-warned :var) all-w))
                           :test     summary
                           :affected (or affected :all)}
                    (pos? existing) (assoc :existing-warnings existing)))))
          {:error (str "no namespace " ns-sym " (ingest it first)")})))))

(defn delete-form!
  "Delete the form named `nm` from `ns-sym`: `:delete` delta, `ns-unmap` in the
  image, verification (tests that exercised it will go red — the honest signal
  if it was still referenced), provenance."
  [session ns-sym nm & {:keys [prompt]}]
  (if-let [[st' delta] (store/remove-form (:store @session) ns-sym nm
                                          :prompt prompt)]
    (do (swap! session assoc :store st')
        (persist-last! session)
        (let [affected (affected-tests session ns-sym nm)]
          (repl/eval! (:image @session) (format "(ns-unmap '%s '%s)" ns-sym nm))
          (let [summary (run-verification! session ns-sym affected)]
            (swap! session update :store store/record-verification ns-sym summary)
            (persist-last! session)
            {:delta delta :test summary :affected (or affected :all)})))
    {:error (str "no form named " nm " in " ns-sym)}))

(defn- apply-group-step
  "Apply one edit-group step to a store VALUE. Returns {:store :delta :hot ...}
  or {:error msg}. `:hot` is the hot-reload action for the commit phase."
  [st gid prompt {:keys [action ns name source]}]
  (case action
    :replace (let [{:keys [node error]} (edit/parse-form source)]
               (if error
                 {:error error}
                 (if-let [[st' d] (store/replace-node st ns name node
                                                      :prompt prompt :group gid)]
                   {:store st' :delta d :hot [:load (:form-id d)]}
                   {:error (str "no form named " name " in " ns)})))
    :add     (let [{:keys [node error]} (edit/parse-form source)
                   nm (some-> node store/form-symbol)]
               (cond
                 error {:error error}
                 (and nm (store/form-named st ns nm))
                 {:error (str nm " already exists in " ns)}
                 :else
                 (if-let [[st' d] (store/append-form st ns node
                                                     :prompt prompt :group gid)]
                   {:store st' :delta d :hot [:load (:form-id d)]}
                   {:error (str "no namespace " ns " (ingest it first)")})))
    :delete  (if-let [[st' d] (store/remove-form st ns name
                                                 :prompt prompt :group gid)]
               {:store st' :delta d :hot [:unmap ns name]}
               {:error (str "no form named " name " in " ns)})
    {:error (str "unknown action: " action)}))

(defn edit-group!
  "Apply several form writes as ONE atomic intent (F2). All steps are validated
  and applied to a store value first — any error rejects the WHOLE group with
  nothing committed (store, deltas, image untouched). On success: all deltas
  (sharing a `:group` id) commit and persist, every change hot-reloads, and
  verification runs ONCE at the end — no meaningless mid-refactor red, no
  wasted diagnostic restart. Steps: [{:action :replace|:add|:delete
  :ns sym :name sym :source str} ...]."
  [session steps & {:keys [prompt]}]
  (if (empty? steps)
    {:error "edit-group needs at least one step"}
    (let [pre-warned (into #{}
                           (mapcat (fn [ns-sym]
                                     (map :var (edit/ns-warnings (:store @session) ns-sym))))
                           (distinct (map :ns steps)))
          [gid st0] (store/alloc-id (:store @session) "g")]
      (loop [st st0, remaining steps, deltas [], hots [], i 0]
        (if-let [step (first remaining)]
          (let [r (apply-group-step st gid prompt step)]
            (if (:error r)
              {:error (str "step " i ": " (:error r)) :step i}
              (recur (:store r) (rest remaining)
                     (conj deltas (:delta r)) (conj hots (:hot r)) (inc i))))
          ;; commit phase — checked loads FIRST (S1), commit only if all compile
          (if-let [load-err (hot-load-all! session st
                                           (keep (fn [[k a]] (when (= :load k) a))
                                                 hots))]
            {:error (str "group failed to compile: " load-err)}
            (let [_        (swap! session assoc :store st)
                  db       (:db @session)
                  _        (doseq [d deltas]
                             (when db (db/persist! db st d)))
                  image    (:image @session)
                  _        (doseq [[kind a b] hots]
                             (when (= :unmap kind)
                               (repl/eval! image (format "(ns-unmap '%s '%s)" a b))))
                  ;; affected = union across steps; any unknown → conservative full run
                  per-step (map (fn [{:keys [action ns name source]}]
                                  (let [nm (case action
                                             :add (some-> (edit/parse-form source) :node
                                                          store/form-symbol)
                                             name)
                                        a  (when nm (affected-tests session ns nm))]
                                    (cond
                                      (some? a)       (set a)
                                      (= action :add) #{}   ; brand-new form: no testers
                                      :else           :unknown)))
                                steps)
                  affected (when (not-any? #{:unknown} per-step)
                             (vec (sort (apply set/union per-step))))
                  main-ns  (:ns (first steps))
                  summary  (run-verification! session main-ns
                                              (when (seq affected) affected))]
              (swap! session update :store store/record-verification main-ns summary)
              (persist-last! session)
              (let [all-w    (->> (map :ns steps) distinct
                                  (mapcat #(edit/ns-warnings (:store @session) %)))
                    existing (count (filter (comp pre-warned :var) all-w))]
                (cond-> {:group    gid
                         :deltas   deltas
                         :warnings (vec (remove (comp pre-warned :var) all-w))
                         :test     summary
                         :affected (or (not-empty affected) :all)}
                  (pos? existing) (assoc :existing-warnings existing))))))))))

(defn add-require!
  "F5: add one require clause to `ns-sym`'s ns form — structural edit through
  the normal replace pipeline (delta, hot-reload, verification)."
  [session ns-sym require-str & {:keys [prompt]}]
  (if-let [f (store/form-named (:store @session) ns-sym ns-sym)]
    (let [r (edit/add-require-source (n/string (:node f)) require-str)]
      (if (:error r)
        r
        (edit-replace! session ns-sym ns-sym (:src r)
                       :prompt (or prompt (str "add require " require-str)))))
    {:error (str "no namespace " ns-sym " (create it first)")}))

(defn remove-require!
  "Symmetric counterpart of add-require!: structurally remove `lib`'s require
  spec from `ns-sym`'s ns form, through the normal replace pipeline."
  [session ns-sym lib & {:keys [prompt]}]
  (if-let [f (store/form-named (:store @session) ns-sym ns-sym)]
    (let [r (edit/remove-require-source (n/string (:node f)) lib)]
      (if (:error r)
        r
        (edit-replace! session ns-sym ns-sym (:src r)
                       :prompt (or prompt (str "remove require " lib)))))
    {:error (str "no namespace " ns-sym)}))

(defn move-form!
  "S2: reorder — move form `nm` to just before `:before` in its namespace (the
  fix for append-only forward references). Image vars are order-independent so
  nothing re-evals; the next fresh load / restart uses the new order."
  [session ns-sym nm & {:keys [before prompt]}]
  (cond
    (nil? (store/form-named (:store @session) ns-sym nm))
    {:error (str "no form named " nm " in " ns-sym)}

    (nil? (store/form-named (:store @session) ns-sym before))
    {:error (str "no form named " before " in " ns-sym)}

    :else
    (if-let [[st' delta] (store/move-form (:store @session) ns-sym nm before
                                          :prompt prompt)]
      (do (swap! session assoc :store st')
          (persist-last! session)
          {:delta delta :moved {:form nm :before before}})
      {:error (str "cannot move " nm)})))

(defn test-run!
  "Traced, diagnosed run of `ns-sym`'s tests (all, or just the plain names in
  `:only`); refreshes the test→form map and records the result (C4)."
  [session ns-sym & {:keys [only]}]
  (let [summary (diagnosed-run! session ns-sym (seq only))]
    (swap! session update :store store/record-verification ns-sym summary)
    (persist-last! session)
    summary))

(defn- forms-changed-since
  "Ids of forms touched by deltas after `since-id` (nil = since the beginning)
  that still exist in the store."
  [store since-id]
  (let [ds   (store/deltas store)
        tail (if since-id
               (rest (drop-while #(not= since-id (:id %)) ds))
               ds)]
    (->> tail
         (mapcat (fn [d] (if (:form-id d) [(:form-id d)] (:form-ids d))))
         distinct
         (filter #(store/ns-of-form-id store %)))))

(defn checkpoint!
  "Mark a unit of work done: deterministically normalize every form changed
  since the last checkpoint (slopp.normalize — conservative behavior-preserving
  rewrites), commit the rewrites as ONE `:normalize` group delta, hot-reload +
  re-verify them, then record a labeled `:checkpoint` boundary delta.
  Returns {:checkpoint id :normalized n :rewrites [{:form :applied}] :test s}."
  [session & {:keys [label]}]
  (let [st       (:store @session)
        changed  (forms-changed-since st (:checkpoint @session))
        rewrites (vec (for [fid changed
                            :let [e (store/form-by-id st fid)
                                  {:keys [node applied]} (normalize/normalize-form (:node e))]
                            :when (seq applied)]
                        {:form-id fid
                         :form    (symbol (str (store/ns-of-form-id st fid))
                                          (str (or (:name e) (:id e))))
                         :node    node
                         :applied applied}))
        summary
        (when (seq rewrites)
          (let [changeset   (into {} (map (juxt :form-id :node)) rewrites)
                main-ns     (store/ns-of-form-id st (:form-id (first rewrites)))
                [st' delta] (store/apply-changeset st :normalize main-ns changeset
                                                   :prompt (or label "checkpoint normalization"))
                touched     (distinct (map #(store/ns-of-form-id st' %) (keys changeset)))]
            (when-let [err (hot-load-all! session st' (keys changeset))]
              (throw (ex-info (str "normalization failed to compile: " err) {})))
            (swap! session assoc :store st')
            (when-let [db (:db @session)] (db/persist! db st' delta touched))
            (let [per      (map (fn [r] (affected-tests session
                                                        (symbol (namespace (:form r)))
                                                        (symbol (name (:form r)))))
                                rewrites)
                  affected (when (not-any? nil? per)
                             (vec (sort (distinct (apply concat per)))))
                  s        (run-verification! session main-ns affected)]
              (swap! session update :store store/record-verification main-ns s)
              (persist-last! session)
              s)))
        ;; kondo lint over every namespace touched since the last checkpoint —
        ;; syntax + best-practice findings, form-addressed (user-requested gate)
        lint (vec (for [ns-sym (distinct (map #(store/ns-of-form-id (:store @session) %)
                                              changed))
                        :let [st* (:store @session)]
                        f (index/lint (render/render-ns st* ns-sym))]
                    (assoc f :ns ns-sym
                           :form (when-let [e (render/owner-form st* ns-sym
                                                                 (:row f) (:col f))]
                                   (symbol (str ns-sym) (str (or (:name e) (:id e))))))))
        [st2 cid] (store/record-checkpoint (:store @session) label)]
    (swap! session assoc :store st2 :checkpoint cid)
    (persist-last! session)
    (cond-> {:checkpoint cid
             :normalized (count rewrites)
             :rewrites   (mapv #(select-keys % [:form :applied]) rewrites)
             :lint       lint}
      summary (assoc :test summary))))

(defn- rename-in-trace
  "Carry the observed test→form map across a rename (old qsym → new qsym)."
  [tmap qold qnew]
  (into {}
        (map (fn [[t forms]]
               [(if (= t qold) qnew t)
                (into #{} (map #(if (= % qold) qnew %)) forms)]))
        tmap))

(defn rename!
  "Rename `ns-sym/old-name` to `new-name` everywhere: ONE coordinated delta over
  the def + every reference across all namespaces (position-based via the index,
  so shadowed locals are untouched — see slopp.refactor). Hot-reloads every
  rewritten form, drops the old var (`ns-unmap`), re-verifies the affected
  tests, and records the outcome. Returns {:delta :renamed :test :affected} or
  {:error msg}."
  [session ns-sym old-name new-name & {:keys [prompt]}]
  (let [st   (:store @session)
        qold (symbol (str ns-sym) (str old-name))
        qnew (symbol (str ns-sym) (str new-name))]
    (cond
      (nil? (store/form-named st ns-sym old-name))
      {:error (str "no form named " old-name " in " ns-sym)}

      (store/form-named st ns-sym new-name)
      {:error (str new-name " already exists in " ns-sym)}

      :else
      (let [changeset    (refactor/rename-changeset st ns-sym old-name new-name)
            [st' delta]  (store/apply-changeset st :rename ns-sym changeset
                                                :prompt prompt
                                                :extra {:old old-name :new new-name})
            touched-nses (distinct (map #(store/ns-of-form-id st' %) (keys changeset)))
            ;; affected tests, judged against the PRE-rename trace map
            changed-syms (into #{qold qnew}
                               (keep (fn [id]
                                       (let [e (store/form-by-id st' id)]
                                         (when (:name e)
                                           (symbol (str (store/ns-of-form-id st' id))
                                                   (str (:name e)))))))
                               (keys changeset))
            tmap         (:test-map @session)
            affected     (when (seq tmap)
                           (let [hits (->> tmap
                                           (keep (fn [[t forms]]
                                                   (when (or (contains? changed-syms t)
                                                             (seq (set/intersection forms changed-syms)))
                                                     (if (= t qold) qnew t))))
                                           sort vec)]
                             (when (seq hits) hits)))
            ;; X2: the renamed DEFINITION must reload before its callers —
            ;; hash-map key order destroyed cross-ns renames at scale
            def-id       (:id (store/form-named st' ns-sym new-name))
            ordered-ids  (into [def-id] (remove #{def-id} (keys changeset)))]
        (if-let [err (hot-load-all! session st' ordered-ids)]
          {:error (str "rename failed to compile: " err)}
          (do
            (swap! session assoc :store st')
            (swap! session update :test-map rename-in-trace qold qnew)
            (when-let [db (:db @session)]
              (db/persist! db st' delta touched-nses))
            (repl/eval! (:image @session)
                        (format "(ns-unmap '%s '%s)" ns-sym old-name))
            (let [summary (run-verification! session ns-sym affected)]
              (swap! session update :store store/record-verification ns-sym summary)
              (persist-last! session)
              {:delta    delta
               :renamed  {:old qold :new qnew :forms (count changeset)}
               :test     summary
               :affected (or affected :all)})))))))

(defn extract!
  "Phase-3 structural op: extract a UNIQUE subform of `from` into a new fn
  `new-name` — params are the free locals in first-use order (computed from
  the index's local analysis), the new fn lands BEFORE `from` (compile order),
  and the subform becomes the call. One atomic intent: three grouped deltas
  (add, move, replace), compile-checked before commit, verified once."
  [session ns-sym from new-name subform-src & {:keys [prompt]}]
  (let [st   (:store @session)
        plan (refactor/extract-plan st ns-sym from subform-src new-name)]
    (cond
      (:error plan) plan

      (store/form-named st ns-sym new-name)
      {:error (str new-name " already exists in " ns-sym)}

      :else
      (let [pd (edit/parse-form (:new-defn-src plan))
            pf (edit/parse-form (:new-from-src plan))]
        (cond
          (:error pd) pd
          (:error pf) pf
          :else
          (let [[gid st0] (store/alloc-id st "g")
                [st1 d1]  (store/append-form st0 ns-sym (:node pd)
                                             :prompt prompt :group gid)
                [st2 d2]  (store/move-form st1 ns-sym new-name from
                                           :prompt prompt :group gid)
                [st3 d3]  (store/replace-node st2 ns-sym from (:node pf)
                                              :prompt prompt :group gid)]
            (if-let [err (hot-load-all! session st3 [(:form-id d1) (:form-id d3)])]
              {:error (str "extract failed to compile: " err)}
              (do (swap! session assoc :store st3)
                  (when-let [db (:db @session)]
                    (doseq [d [d1 d2 d3]] (db/persist! db st3 d)))
                  (let [affected (affected-tests session ns-sym from)
                        summary  (run-verification! session ns-sym affected)]
                    (swap! session update :store store/record-verification
                           ns-sym summary)
                    (persist-last! session)
                    {:extracted {:new    (symbol (str ns-sym) (str new-name))
                                 :params (:params plan)}
                     :group    gid
                     :test     summary
                     :affected (or affected :all)})))))))))

(defn build!
  "C1/C6 explicit build: materialize a runnable project under `dir` —
  `src/<ns-path>.clj` per namespace plus a minimal `deps.edn` (F8). Guarded
  (X4: an eval agent once built into the host repo, clobbering its deps.edn):
  absolute paths only, never a directory enclosing the running process, and an
  existing deps.edn is never overwritten."
  [session dir]
  (let [f      (io/file dir)
        target (.getCanonicalFile f)
        cwd    (.getCanonicalFile (io/file "."))]
    (cond
      (not (.isAbsolute f))
      {:error "build needs an ABSOLUTE directory path"}

      (.startsWith (.toPath cwd) (.toPath target))
      {:error (str "refusing to build into " target
                   " — it contains the running system")}

      :else
      (do (doseq [ns-sym (keys (:namespaces (:store @session)))]
            (let [file (io/file target "src" (render/ns-path ns-sym))]
              (io/make-parents file)
              (spit file (render/render-ns (:store @session) ns-sym))))
          (let [de (io/file target "deps.edn")]
            (when-not (.exists de)
              (spit de "{:paths [\"src\"]}\n")))
          {:built (str target)}))))
