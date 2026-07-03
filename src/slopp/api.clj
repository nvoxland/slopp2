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
            [slopp.build :as build]
            [slopp.db :as db]))

(declare run-verification! forms-changed-since query-outline
         hot-load-all! fresh-image!)

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
                        :persist-agent (agent nil)
                        :dir dir :branch "main" :lines {}
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
  (when-let [pa (:persist-agent @session)]
    (await pa))                                   ; drain queued persists
  (when-let [^java.sql.Connection conn (:db @session)]
    (.close conn))
  (doseq [[_ line] (:lines @session)]
    (when-let [^java.sql.Connection c (:conn line)]
      (.close c)))
  nil)

(def ^:dynamic *pre-commit-hook*
  "Test seam (item 4): invoked between an op's hot-load and its commit CAS to
  simulate a concurrent competitor deterministically. Never set in production."
  nil)

(defn- try-commit!
  "CAS the session store from `base` (by identity) to `st'`. True iff won —
  the heart of CRDT-aligned commits (item 4): no locks, losers rebase."
  [session base st']
  (let [[old _] (swap-vals! session
                            (fn [s]
                              (if (identical? (:store s) base)
                                (assoc s :store st')
                                s)))]
    (identical? (:store old) base)))

(defn- persist-async!
  "Ordered write-through (C7 + item 4): persists queue on a per-session agent
  in send order; element rows are derived from the session's CURRENT store at
  execution time, so they can never regress under concurrent commits (the
  delta row itself is the captured value). No-op for ephemeral sessions."
  [session delta & [nses]]
  (when (:db @session)
    (send-off (:persist-agent @session)
              (fn [_]
                (try
                  (let [{:keys [db store]} @session]
                    (if nses
                      (db/persist! db store delta nses)
                      (db/persist! db store delta)))
                  (catch Throwable _))
                nil))))

(defn- persist-last!
  "Queue the store's newest delta for ordered persistence."
  [session]
  (persist-async! session (last (store/deltas (:store @session)))))

(defn- rebased-write!
  "Run a single-form write with an atomic rebasing commit (item 4, the
  granularity dodge). The pure `transform` (store → {:store :delta ...} |
  {:error}) runs INSIDE swap!, so concurrent different-form writes rebase and
  land without locks or starvation; if the TARGET form itself changed since
  this op began (`target-node`: store → CST node), the commit aborts with
  {:conflict ...} — C5's MV-register semantics, Phase-1 face.
  The compile gate runs once, before commit: the form's CONTENT (what the
  image compiles) is invariant across rebases."
  [session transform target-node target-desc & {:keys [load?] :or {load? true}}]
  (let [base0 (:store @session)
        orig  (some-> (target-node base0) n/string)
        out0  (transform base0)]
    (if (:error out0)
      out0
      (let [load-res (when load?
                       (hot-load-all! session (:store out0)
                                      [(:form-id (:delta out0))]))]
        (if (:err load-res)
          {:error (str "form failed to compile: " (:err load-res))}
          (do (when *pre-commit-hook* (*pre-commit-hook*))
              (let [res (volatile! nil)]
                (swap! session update :store
                       (fn [base]
                         (if (not= orig (some-> (target-node base) n/string))
                           (do (vreset! res
                                        {:conflict {:form target-desc
                                                    :reason "form changed concurrently — re-read and retry"}})
                               base)
                           (let [out (transform base)]
                             (if (:error out)
                               (do (vreset! res out) base)
                               (do (vreset! res out) (:store out)))))))
                (cond-> @res
                  (and (nil? (:error @res)) (nil? (:conflict @res))
                       (:healed load-res))
                  (assoc :image-healed true)))))))))

(defn- with-ms
  "Attach total op wall time (item 2 observability)."
  [m t0]
  (if (map? m)
    (assoc m :ms (quot (- (System/nanoTime) t0) 1000000))
    m))

(defn ingest!
  "The batch write for BRAND-NEW namespaces (W1, user decision): land a whole
  namespace's source in one call. Compile-gated like every write (the image
  loads it FIRST; a failed load commits nothing — T4), then verified and
  recorded like every write. Overwriting an existing namespace is NOT allowed
  — edit its forms instead. Returns {:ns :forms :test} or {:error msg}."
  [session ns-sym source & {:keys [agent]}]
  (if (get-in (:store @session) [:namespaces ns-sym])
    {:error (str ns-sym " already exists — edit its forms instead"
                 " (whole-namespace overwrite is not allowed)")}
    (try
      (let [candidate (store/ingest (:store @session) ns-sym source :agent agent)
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
              (let [edited  (into #{}
                                  (keep (fn [e]
                                          (when (:name e)
                                            (symbol (str ns-sym) (str (:name e))))))
                                  (store/forms candidate ns-sym))
                    summary (run-verification! session ns-sym nil :edited edited)]
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
  "Usages of `ns-sym/nm` across EVERY namespace (F-3c3 — same-ns-only results
  sent an eval agent to query_search instead; analyses are memo-cached, so the
  full scan is cheap)."
  [session ns-sym nm]
  (let [st (:store @session)]
    (vec (mapcat (fn [n]
                   (index/references (index/analyze (render/render-ns st n))
                                     ns-sym nm))
                 (sort (keys (:namespaces st)))))))

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
       (mapv #(select-keys % [:id :op :ns :prompt :label :group :agent
                              :form-id :form-ids :old :new :before]))))

(defn query-project
  "The WHOLE store's shape in one call: every namespace with its outline
  (item 1 — orientation was ~90% of tool calls in successful runs; this
  replaces the namespaces→outline×N chain)."
  [session]
  (mapv (fn [ns-sym] (query-outline session ns-sym))
        (sort (keys (:namespaces (:store @session))))))

(defn query-search
  "The missing grep: regex over all store source, form-addressed results
  [{:ns :form :line}], capped at `:limit` (default 30)."
  [session pattern & {:keys [limit] :or {limit 30}}]
  (try
    (let [re (re-pattern pattern)
          st (:store @session)]
      (->> (for [ns-sym (sort (keys (:namespaces st)))
                 e      (store/forms st ns-sym)
                 line   (str/split-lines (n/string (:node e)))
                 :when  (re-find re line)]
             {:ns ns-sym
              :form (or (:name e) (:id e))
              :line (str/trim line)})
           (take limit)
           vec))
    (catch Exception ex
      {:error (str "bad pattern: " (ex-message ex))})))

(defn query-eval
  "Observe-only eval against the live image (the oracle): call anything —
  including effectful fns — but (re)defining code is rejected (T5); writes go
  through the edit tools so provenance stays airtight."
  [session code]
  (if-let [err (edit/observe-gate code)]
    {:error err}
    (let [r (repl/eval-checked! (:image @session) code)]
      (if (:err r)                                  ; F-3c2: never a silent []
        {:error (:err r)}
        (:values r)))))

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
  Returns nil on success, {:healed true} when a STALE IMAGE had to be
  refreshed to make the load succeed (D5.1 — e.g. a var the store defines was
  missing from the image), or {:err msg} when the forms genuinely don't
  compile (image restored either way)."
  [session candidate form-ids]
  (letfn [(load-all []
            (loop [ids (seq form-ids)]
              (when ids
                (or (edit/hot-load-form! (:image @session) candidate (first ids))
                    (recur (next ids))))))]
    (when-let [_err (load-all)]
      (fresh-image! session)                 ; maybe the image was stale
      (if-let [err2 (load-all)]
        (do (fresh-image! session) {:err err2})
        {:healed true}))))

(defn- traced-run!
  "Run `test-ns`'s tests (all, or `only` names) with form-tracing; absorb the
  observed test→form map into the session; return the summary."
  [session test-ns only]
  (let [{:keys [image store]} @session
        {:keys [summary trace]} (image/traced-test-run image store test-ns :only only)]
    (swap! session update :test-map merge trace)
    summary))

(def ^:private reload-signature-res
  "Failure texts that smell like hot-reload staleness rather than logic bugs."
  [#"Unable to resolve symbol"
   #"Attempting to call unbound fn"
   #"No implementation of method"
   #"Var .* is unbound"])

(defn- reload-signature? [failure]
  (let [s (str (:actual failure) " " (:message failure))]
    (or (boolean (some #(re-find % s) reload-signature-res))
        ;; same-named classes cast-failing against each other = redefined type
        (boolean
         (when-let [[_ c1 c2] (re-find #"class (\S+) cannot be cast to class (\S+)" s)]
           (= (last (str/split c1 #"\.")) (last (str/split c2 #"\."))))))))

(defn- suspicious-red?
  "Could this red plausibly be image staleness rather than a genuine failure
  (D5.1)? Yes iff: no edit context; a truncated failure list; a
  reload-signature failure; or an UNEXPLAINED FLIP — a failing test whose
  traced form-set doesn't intersect the just-edited forms and which wasn't
  itself edited (this also catches value-capture staleness, since captured
  calls bypass the trace)."
  [session edited summary]
  (let [tmap       (:test-map @session)
        failures   (:failures summary)
        truncated? (> (+ (:fail summary 0) (:error summary 0)) (count failures))]
    (or (nil? edited)
        truncated?
        (boolean (some reload-signature? failures))
        (boolean
         (some (fn [f]
                 (let [t       (:test f)
                       touched (get tmap t)]
                   (or (nil? touched)
                       (and (not (contains? edited t))
                            (empty? (set/intersection touched edited))))))
               failures)))))

(defn- diagnosed-run!
  "Run tests. Reds cross-check on a fresh image ONLY when staleness is
  plausible (D5.1: reload signatures, unexplained flips, missing provenance);
  a red clearly caused by the just-edited forms returns immediately as
  {:diagnosis :genuine} — no restart, no second run. `:fresh true` restarts
  FIRST and runs once against a guaranteed-faithful image."
  [session test-ns only & {:keys [edited fresh]}]
  (when fresh (fresh-image! session))
  (let [r1 (traced-run! session test-ns only)]
    (cond
      (green? r1) r1

      fresh (assoc r1 :fresh-confirmed true)

      (suspicious-red? session edited r1)
      (do (fresh-image! session)
          (let [r2 (traced-run! session test-ns only)]
            (if (green? r2)
              (assoc r2 :staleness-detected true)
              (assoc r2 :fresh-confirmed true))))

      :else (assoc r1 :diagnosis :genuine))))

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
  `default-ns`'s tests when there's no trace information. `:edited` (the
  just-changed form qsyms) powers the D5.1 genuine-vs-suspicious call."
  [session default-ns affected & {:keys [edited fresh]}]
  (if (nil? affected)
    (diagnosed-run! session default-ns nil :edited edited :fresh fresh)
    (reduce (fn [acc [tns tsyms]]
              (merge-with (fn [a b]
                            (cond (number? a) (+ a b)
                                  (and (sequential? a) (sequential? b)) (into (vec a) b)
                                  :else (or b a)))
                          acc
                          (diagnosed-run! session tns (mapv (comp symbol name) tsyms)
                                          :edited edited :fresh fresh)))
            {}
            (group-by (comp symbol namespace) affected))))

;; --- edit.* / runtime ---

(defn edit-replace!
  "Replace the form `nm` in `ns-sym` with `new-source` (O1 whole-form replace):
  pipeline + hot-reload, then re-verify — only the tests the trace map says
  exercise this form (D1), cross-checked on a fresh image if red (D5) — and
  record the outcome as provenance (C4)."
  [session ns-sym nm new-source & {:keys [prompt agent]}]
  (let [t0 (System/nanoTime)
        pre-warned (set (map :var (edit/ns-warnings (:store @session) ns-sym)))
        r (rebased-write!
           session
           (fn [base] (edit/replace-form base ns-sym nm new-source
                                         :prompt prompt :agent agent))
           (fn [base] (:node (store/form-named base ns-sym nm)))
           (symbol (str ns-sym) (str nm)))]
    (if (or (:error r) (:conflict r))
      r
      (let [_        (persist-last! session)          ; the :replace delta
            qform    (symbol (str ns-sym) (str nm))
            new-nm   (:name (store/form-by-id (:store r)
                                              (:form-id (:delta r))))
            edited   (into #{qform}
                           (when new-nm [(symbol (str ns-sym) (str new-nm))]))
            affected (affected-tests session ns-sym nm)
            untested (and (nil? affected) (seq (:test-map @session)))
            summary  (run-verification! session ns-sym affected
                                        :edited edited)
            existing (count (filter (comp pre-warned :var) (:warnings r)))]
        (swap! session update :store store/record-verification ns-sym summary)
        (persist-last! session)                       ; the :verify delta
        (with-ms
          (cond-> {:delta    (:delta r)
                   ;; T3: only NEW violations; pre-existing ones as a count
                   :warnings (vec (remove (comp pre-warned :var) (:warnings r)))
                   :test     summary
                   :affected (or affected :all)}
            (:image-healed r) (assoc :image-healed true)
            (pos? existing)   (assoc :existing-warnings existing)
            untested          (assoc :untested true))
          t0)))))

(defn add-form!
  "Add a new top-level form to `ns-sym` (O1 base write): dialect gate, `:add`
  delta, hot-reload into the image, verification, provenance. Returns
  {:delta :warnings :test :affected} or {:error msg}."
  [session ns-sym source & {:keys [prompt agent]}]
  (let [t0 (System/nanoTime)
        {:keys [node error]} (edit/parse-form source)
        nm (some-> node store/form-symbol)]
    (cond
      error {:error error}

      (and nm (store/form-named (:store @session) ns-sym nm))
      {:error (str nm " already exists in " ns-sym)}

      :else
      (let [pre-warned (set (map :var (edit/ns-warnings (:store @session) ns-sym)))
            r (rebased-write!
               session
               (fn [base]
                 (cond
                   (and nm (store/form-named base ns-sym nm))
                   {:error (str nm " already exists in " ns-sym)}
                   :else
                   (if-let [[st' d] (store/append-form base ns-sym node
                                                       :prompt prompt :agent agent)]
                     {:store st' :delta d}
                     {:error (str "no namespace " ns-sym " (ingest it first)")})))
               (fn [base] (when nm (:node (store/form-named base ns-sym nm))))
               (symbol (str ns-sym) (str (or nm "anonymous"))))]
        (if (or (:error r) (:conflict r))
          r
          (do (persist-last! session)
              (let [edited   (if nm #{(symbol (str ns-sym) (str nm))} #{})
                    affected (when nm (affected-tests session ns-sym nm))
                    summary  (run-verification! session ns-sym affected
                                                :edited edited)
                    all-w    (edit/ns-warnings (:store @session) ns-sym)
                    existing (count (filter (comp pre-warned :var) all-w))]
                (swap! session update :store store/record-verification ns-sym summary)
                (persist-last! session)
                (with-ms
                  (cond-> {:delta    (:delta r)
                           ;; T3: only NEW violations; pre-existing as a count
                           :warnings (vec (remove (comp pre-warned :var) all-w))
                           :test     summary
                           :affected (or affected :all)}
                    (:image-healed r) (assoc :image-healed true)
                    (pos? existing)   (assoc :existing-warnings existing))
                  t0))))))))

(defn delete-form!
  "Delete the form named `nm` from `ns-sym`: `:delete` delta, `ns-unmap` in the
  image, verification (tests that exercised it will go red — the honest signal
  if it was still referenced), provenance."
  [session ns-sym nm & {:keys [prompt agent]}]
  (let [r (rebased-write!
           session
           (fn [base]
             (if-let [[st' d] (store/remove-form base ns-sym nm
                                                 :prompt prompt :agent agent)]
               {:store st' :delta d}
               {:error (str "no form named " nm " in " ns-sym)}))
           (fn [base] (:node (store/form-named base ns-sym nm)))
           (symbol (str ns-sym) (str nm))
           :load? false)]
    (if (or (:error r) (:conflict r))
      r
      (do (persist-last! session)
          (let [affected (affected-tests session ns-sym nm)]
            (repl/eval! (:image @session) (format "(ns-unmap '%s '%s)" ns-sym nm))
            (let [summary (run-verification! session ns-sym affected
                                             :edited #{(symbol (str ns-sym) (str nm))})]
              (swap! session update :store store/record-verification ns-sym summary)
              (persist-last! session)
              {:delta (:delta r) :test summary :affected (or affected :all)}))))))

(defn- apply-group-step
  "Apply one edit-group step to a store VALUE. Returns {:store :delta :hot ...}
  or {:error msg}. `:hot` is the hot-reload action for the commit phase."
  [st gid prompt agent {:keys [action ns name source]}]
  (case action
    :replace (let [{:keys [node error]} (edit/parse-form source)]
               (if error
                 {:error error}
                 (if-let [[st' d] (store/replace-node st ns name node
                                                      :prompt prompt :group gid
                                                      :agent agent)]
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
                                                     :prompt prompt :group gid
                                                     :agent agent)]
                   {:store st' :delta d :hot [:load (:form-id d)]}
                   {:error (str "no namespace " ns " (ingest it first)")})))
    :delete  (if-let [[st' d] (store/remove-form st ns name
                                                 :prompt prompt :group gid
                                                 :agent agent)]
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
  [session steps & {:keys [prompt agent]}]
  (if (empty? steps)
    {:error "edit-group needs at least one step"}
    (let [t0 (System/nanoTime)
          base0 (:store @session)
          pre-warned (into #{}
                           (mapcat (fn [ns-sym]
                                     (map :var (edit/ns-warnings (:store @session) ns-sym))))
                           (distinct (map :ns steps)))
          [gid st0] (store/alloc-id base0 "g")]
      (loop [st st0, remaining steps, deltas [], hots [], i 0]
        (if-let [step (first remaining)]
          (let [r (apply-group-step st gid prompt agent step)]
            (if (:error r)
              {:error (str "step " i ": " (:error r)) :step i}
              (recur (:store r) (rest remaining)
                     (conj deltas (:delta r)) (conj hots (:hot r)) (inc i))))
          ;; commit phase — checked loads FIRST (S1), commit only if all compile
          (let [load-res (hot-load-all! session st
                                        (keep (fn [[k a]] (when (= :load k) a))
                                              hots))]
            (cond
              (:err load-res)
              {:error (str "group failed to compile: " (:err load-res))}

              (not (try-commit! session base0 st))
              {:conflict {:reason "store changed during multi-form op — retry"}}

              :else
              (let [_        (doseq [d deltas]
                               (persist-async! session d))
                    image    (:image @session)
                    _        (doseq [[kind a b] hots]
                               (when (= :unmap kind)
                                 (repl/eval! image (format "(ns-unmap '%s '%s)" a b))))
                    ;; per-step names double as the D5.1 edited set
                    step-nms (map (fn [{:keys [action ns name source]}]
                                    (let [nm (case action
                                               :add (some-> (edit/parse-form source)
                                                            :node store/form-symbol)
                                               name)]
                                      (when nm [action ns nm])))
                                  steps)
                    edited   (into #{}
                                   (keep (fn [x]
                                           (when-let [[_ ns nm] x]
                                             (symbol (str ns) (str nm)))))
                                   step-nms)
                    ;; affected = union across steps; unknown → conservative full
                    per-step (map (fn [x]
                                    (if-let [[action ns nm] x]
                                      (let [a (affected-tests session ns nm)]
                                        (cond
                                          (some? a)       (set a)
                                          (= action :add) #{}
                                          :else           :unknown))
                                      :unknown))
                                  step-nms)
                    affected (when (not-any? #{:unknown} per-step)
                               (vec (sort (apply set/union per-step))))
                    ;; F-3c5: with no/partial trace info the fallback run must
                    ;; cover EVERY touched namespace, not just the first step's
                    main-ns  (vec (distinct (map :ns steps)))
                    summary  (run-verification! session main-ns
                                                (when (seq affected) affected)
                                                :edited edited)]
                (swap! session update :store store/record-verification main-ns summary)
                (persist-last! session)
                (let [all-w    (->> (map :ns steps) distinct
                                    (mapcat #(edit/ns-warnings (:store @session) %)))
                      existing (count (filter (comp pre-warned :var) all-w))]
                  (with-ms
                    (cond-> {:group    gid
                             :deltas   deltas
                             :warnings (vec (remove (comp pre-warned :var) all-w))
                             :test     summary
                             :affected (or (not-empty affected) :all)}
                      (:healed load-res) (assoc :image-healed true)
                      (pos? existing)    (assoc :existing-warnings existing))
                    t0))))))))))

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
  [session ns-sym nm & {:keys [before prompt agent]}]
  (cond
    (nil? (store/form-named (:store @session) ns-sym nm))
    {:error (str "no form named " nm " in " ns-sym)}

    (nil? (store/form-named (:store @session) ns-sym before))
    {:error (str "no form named " before " in " ns-sym)}

    :else
    (let [base0 (:store @session)]
      (if-let [[st' delta] (store/move-form base0 ns-sym nm before
                                            :prompt prompt :agent agent)]
        (if-not (try-commit! session base0 st')
          {:conflict {:reason "store changed concurrently — retry"}}
          (do (persist-last! session)
              {:delta delta :moved {:form nm :before before}}))
        {:error (str "cannot move " nm)}))))

(defn test-run!
  "Traced, diagnosed run of `ns-sym`'s tests (all, or just the plain names in
  `:only`); refreshes the test→form map and records the result (C4).
  `ns-sym` nil = the WHOLE project in one image eval, instrumentation paid
  once (F-3c1 — per-ns sweeps were 12 calls and 12 instrumentation passes).
  D5.1: reds are judged against the forms changed since the last verification;
  `:fresh true` restarts first for a guaranteed-faithful single run."
  [session ns-sym & {:keys [only fresh]}]
  (let [t0          (System/nanoTime)
        st          (:store @session)
        ns-sym      (or ns-sym (vec (sort (keys (:namespaces st)))))
        last-verify (:id (last (filter #(= :verify (:op %)) (store/deltas st))))
        edited      (into #{}
                          (keep (fn [id]
                                  (when-let [e (store/form-by-id st id)]
                                    (symbol (str (store/ns-of-form-id st id))
                                            (str (or (:name e) (:id e)))))))
                          (forms-changed-since st last-verify))
        summary     (diagnosed-run! session ns-sym (seq only)
                                    :edited edited :fresh fresh)]
    (swap! session update :store store/record-verification ns-sym summary)
    (persist-last! session)
    (with-ms summary t0)))

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
            (when-let [err (:err (hot-load-all! session st' (keys changeset)))]
              (throw (ex-info (str "normalization failed to compile: " err) {})))
            (when-not (try-commit! session st st')
              (throw (ex-info "store changed during checkpoint — retry" {})))
            (persist-async! session delta touched)
            (let [per      (map (fn [r] (affected-tests session
                                                        (symbol (namespace (:form r)))
                                                        (symbol (name (:form r)))))
                                rewrites)
                  affected (when (not-any? nil? per)
                             (vec (sort (distinct (apply concat per)))))
                  s        (run-verification! session main-ns affected
                                              :edited (set (map :form rewrites)))]
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
        cid (let [v (volatile! nil)]
              (swap! session
                     (fn [sess-map]
                       (let [[st2 c] (store/record-checkpoint (:store sess-map) label)]
                         (vreset! v c)
                         (assoc sess-map :store st2 :checkpoint c))))
              @v)]
    (persist-last! session)
    (cond-> {:checkpoint cid
             :normalized (count rewrites)
             :rewrites   (mapv #(select-keys % [:form :applied]) rewrites)
             :lint       lint}
      summary (assoc :test summary))))

(defn edit-subform!
  "Item 5 — paredit's invariant, agent-shaped: replace the UNIQUE structural
  occurrence of `match` inside form `form-name` with `new-src`
  (content-addressed; wrap/unwrap are just 'new subform containing/omitting
  the old'). The payload scales with the CHANGE and sibling code is never
  re-transcribed. Rides the full replace pipeline: dialect gate on the
  RESULTING form, rebase/conflict commit, verification, provenance."
  [session ns-sym form-name match new-src & {:keys [prompt agent]}]
  (let [plan (refactor/subform-replace-plan (:store @session) ns-sym form-name
                                            match new-src)]
    (if (:error plan)
      plan
      (edit-replace! session ns-sym form-name (:new-form-src plan)
                     :prompt (or prompt (str "subform edit in " form-name))
                     :agent agent))))

(defn revert-form!
  "One-call rollback (item 4): replace `nm` with an earlier version of itself —
  by default the previous one, or the version at delta `:to` (see
  query-form-history). Rides the standard replace pipeline, so the revert is
  itself compile-gated, verified, and recorded provenance."
  [session ns-sym nm & {:keys [to prompt agent]}]
  (let [hist (query-form-history session ns-sym nm)]
    (cond
      (nil? hist)
      {:error (str "no form named " nm " in " ns-sym)}

      (< (count hist) 2)
      {:error (str nm " has no earlier version to revert to")}

      :else
      (let [target (if to
                     (first (filter #(= to (:delta %)) hist))
                     (nth hist (- (count hist) 2)))]
        (if-not target
          {:error (str "no version of " nm " at delta " to)}
          (edit-replace! session ns-sym nm (:source target)
                         :prompt (or prompt
                                     (str "revert to " (:delta target)))
                         :agent agent))))))

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
  [session ns-sym old-name new-name & {:keys [prompt agent]}]
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
                                                :prompt prompt :agent agent
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
        (if-let [err (:err (hot-load-all! session st' ordered-ids))]
          {:error (str "rename failed to compile: " err)}
          (if-not (try-commit! session st st')
            {:conflict {:reason "store changed during rename — retry"}}
            (do
              (swap! session update :test-map rename-in-trace qold qnew)
              (persist-async! session delta touched-nses)
              (repl/eval! (:image @session)
                          (format "(ns-unmap '%s '%s)" ns-sym old-name))
              (let [summary (run-verification! session ns-sym affected
                                               :edited changed-syms)]
                (swap! session update :store store/record-verification ns-sym summary)
                (persist-last! session)
                {:delta    delta
                 :renamed  {:old qold :new qnew :forms (count changeset)}
                 :test     summary
                 :affected (or affected :all)}))))))))

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
            (if-let [err (:err (hot-load-all! session st3
                                              [(:form-id d1) (:form-id d3)]))]
              {:error (str "extract failed to compile: " err)}
              (if-not (try-commit! session st st3)
                {:conflict {:reason "store changed during extract — retry"}}
                (do (doseq [d [d1 d2 d3]] (persist-async! session d))
                    (let [affected (affected-tests session ns-sym from)
                          summary  (run-verification! session ns-sym affected
                                                      :edited
                                                      #{(symbol (str ns-sym) (str from))
                                                        (symbol (str ns-sym) (str new-name))})]
                      (swap! session update :store store/record-verification
                             ns-sym summary)
                      (persist-last! session)
                      {:extracted {:new    (symbol (str ns-sym) (str new-name))
                                   :params (:params plan)}
                       :group    gid
                       :test     summary
                       :affected (or affected :all)}))))))))))

(defn- merge-into-session!
  "Shared merge pipeline (m2 forks + m3 branches): replay `theirs` onto the
  session store (store/merge-logs), hot-load what arrived (new namespaces in
  dependency order, then changed forms through the compile gate), commit,
  persist, verify every touched namespace, and record ONE `:merge` delta."
  [session theirs from-label]
  (let [t0   (System/nanoTime)
        base (:store @session)
        r    (store/merge-logs base theirs :from from-label)]
    (cond
      (nil? (:fork-point r))
      {:error "stores share no history — not a fork/branch of this project"}

      (and (zero? (:merged r)) (empty? (:conflicts r)))
      {:merged 0 :conflicts [] :note "already converged — nothing to merge"}

      :else
      (let [st'      (:store r)
            load-err (or ;; new namespaces first, dependency order
                      (some (fn [ns-sym]
                              (when (contains? (set (:new-nses r)) ns-sym)
                                (image/load-ns! (:image @session) st' ns-sym)))
                            (store/ns-dependency-order st'))
                      ;; then every changed form (compile gate, heals)
                      (:err (hot-load-all! session st'
                                           (:changed-form-ids r))))]
        (if load-err
          (do (fresh-image! session)
              {:error (str "merge failed to compile: " load-err)})
          (let [[st'' mdelta] (store/record-merge st' from-label r)]
            (if-not (try-commit! session base st'')
              {:conflict {:reason "store changed during merge — retry"}}
              (let [new-deltas   (drop (count (store/deltas base))
                                       (store/deltas st''))
                    touched-nses (vec (distinct
                                       (concat (keep :ns new-deltas)
                                               (:new-nses r))))
                    _            (doseq [d new-deltas]
                                   (persist-async! session d
                                                   (filterv #(get-in st'' [:namespaces %])
                                                            touched-nses)))
                    edited       (into #{}
                                       (keep (fn [id]
                                               (when-let [e (store/form-by-id st'' id)]
                                                 (symbol (str (store/ns-of-form-id st'' id))
                                                         (str (or (:name e) (:id e)))))))
                                       (:changed-form-ids r))
                    verify-nses  (vec (remove #{'*session*} touched-nses))
                    summary      (when (seq verify-nses)
                                   (run-verification! session verify-nses nil
                                                      :edited edited))]
                (when summary
                  (swap! session update :store
                         store/record-verification verify-nses summary)
                  (persist-last! session))
                (with-ms
                  (cond-> {:merged     (:merged r)
                           :conflicts  (:conflicts r)
                           :merge-delta (:id mdelta)}
                    (seq (:new-nses r)) (assoc :new-nses (:new-nses r))
                    (seq (:notes r))    (assoc :notes (:notes r))
                    summary             (assoc :test summary))
                  t0)))))))))

(defn merge!
  "Phase 4 m2: merge a DIVERGED COPY of this project back into the live
  session. A 'fork' is just a copied project dir edited by its own slopp
  server; `other-dir` is that copy. Their delta-log suffix replays onto our
  store: different-form work lands, identical changes converge, same-form
  divergence returns `:conflicts` (ours kept, theirs surfaced — resolve by
  hand with edit_replace_form)."
  [session other-dir]
  (let [f    (io/file (str other-dir))
        db-f (io/file f ".slopp" "store.db")]
    (cond
      (not (.isAbsolute f))
      {:error "merge needs an ABSOLUTE project-dir path"}

      (not (.exists db-f))
      {:error (str "no slopp store under " other-dir)}

      :else
      (let [conn   (db/open! (str f))
            theirs (try (db/load-store conn)
                        (finally (.close ^java.sql.Connection conn)))]
        (merge-into-session! session theirs (str other-dir))))))

;; --- Phase 4 m3: branches within one repo -------------------------------

(defn- line-dir
  "Where a branch line persists in a durable session."
  [dir nm]
  (str (io/file dir ".slopp" "branches" nm)))

(defn- snapshot-to-conn!
  "Full-store snapshot into a (fresh) branch db: every delta + all elements."
  [conn store]
  (let [ds   (store/deltas store)
        nses (vec (keys (:namespaces store)))]
    (doseq [d (butlast ds)] (db/persist! conn store d []))
    (when-let [d (last ds)] (db/persist! conn store d nses))))

(defn- delete-dir! [^java.io.File f]
  (when (.exists f)
    (doseq [^java.io.File c (reverse (file-seq f))] (.delete c))))

(defn- load-line
  "An inactive line's {:store :conn}: from memory, or lazily from its branch
  db in a durable session. nil if unknown."
  [session nm]
  (let [{:keys [lines dir]} @session]
    (or (get lines nm)
        (when (and dir (.exists (io/file (line-dir dir nm) ".slopp" "store.db")))
          (let [c (db/open! (line-dir dir nm))]
            {:store (db/load-store c) :conn c})))))

(defn branch!
  "Phase 4 m3: create branch `nm` from the CURRENT line's state and switch to
  it — O(1), the store is a value; the image is already correct (identical
  content). Durable sessions snapshot the line under .slopp/branches/<nm>."
  [session nm]
  (let [nm (str nm)
        {:keys [branch lines dir]} @session]
    (cond
      (str/blank? nm)
      {:error "branch needs a name"}

      (= nm "main")
      {:error "main is the trunk — branch FROM it"}

      (or (= nm branch)
          (contains? lines nm)
          (and dir (.exists (io/file (line-dir dir nm)))))
      {:error (str "branch " nm " already exists")}

      :else
      (do (when-let [pa (:persist-agent @session)] (await pa))
          (let [conn (when dir
                       (doto (db/open! (line-dir dir nm))
                         (snapshot-to-conn! (:store @session))))]
            (swap! session
                   (fn [s]
                     (-> s
                         (update :lines assoc (:branch s)
                                 {:store (:store s) :conn (:db s)})
                         (assoc :branch nm :db conn))))
            {:branch nm :from branch})))))

(defn branch-switch!
  "Checkout: swap the session to line `nm` and bring the ONE live image in
  step (only namespaces whose source differs reload; a removed namespace
  forces a fresh image). The trace map resets — it described the other line."
  [session nm]
  (let [nm (str nm)]
    (if (= nm (:branch @session))
      {:switched nm :note "already on it"}
      (if-let [target (load-line session nm)]
        (do (when-let [pa (:persist-agent @session)] (await pa))
            (let [old-store (:store @session)
                  new-store (:store target)
                  removed   (remove #(get-in new-store [:namespaces %])
                                    (keys (:namespaces old-store)))
                  changed   (vec (filter #(not= (render/render-ns old-store %)
                                                (render/render-ns new-store %))
                                         (store/ns-dependency-order new-store)))]
              (swap! session
                     (fn [s]
                       (-> s
                           (update :lines assoc (:branch s)
                                   {:store (:store s) :conn (:db s)})
                           (update :lines dissoc nm)
                           (assoc :branch nm
                                  :db (:conn target)
                                  :store new-store
                                  :test-map {}))))
              (if (seq removed)
                (fresh-image! session)
                (when (some #(image/load-ns! (:image @session) new-store %)
                            changed)
                  (fresh-image! session)))          ; any load error → heal fully
              {:switched nm :reloaded (if (seq removed) :all changed)}))
        {:error (str "no branch named " nm)}))))

(defn branch-merge!
  "Merge branch `nm` into the CURRENT line (switch to main first to merge
  down). Same engine and semantics as fork merges, iterated merges included;
  the branch survives and can keep going."
  [session nm]
  (let [nm (str nm)]
    (if (= nm (:branch @session))
      {:error "cannot merge a branch into itself — switch to the target line first"}
      (if-let [target (load-line session nm)]
        (let [res (merge-into-session! session (:store target)
                                       (str "branch:" nm))]
          ;; lazily-opened conn is only needed for reading here
          (when (and (:conn target)
                     (not (contains? (:lines @session) nm)))
            (.close ^java.sql.Connection (:conn target)))
          res)
        {:error (str "no branch named " nm)}))))

(defn branch-delete!
  "Drop branch `nm` (never the one you are on). Durable sessions also remove
  its .slopp/branches dir."
  [session nm]
  (let [nm (str nm)
        {:keys [branch lines dir]} @session]
    (cond
      (= nm branch)
      {:error "cannot delete the branch you are on"}

      (not (or (contains? lines nm)
               (and dir (.exists (io/file (line-dir dir nm))))))
      {:error (str "no branch named " nm)}

      :else
      (do (some-> (get-in lines [nm :conn])
                  ^java.sql.Connection (.close))
          (swap! session update :lines dissoc nm)
          (when dir (delete-dir! (io/file (line-dir dir nm))))
          {:deleted nm}))))

(defn query-branches
  "Every line in the repo: the current one, in-memory lines, and (durable)
  on-disk branches not yet loaded this session."
  [session]
  (let [{:keys [branch lines dir store]} @session
        on-disk (when dir
                  (let [bdir (io/file dir ".slopp" "branches")]
                    (when (.exists bdir)
                      (map #(.getName ^java.io.File %)
                           (filter #(.isDirectory ^java.io.File %)
                                   (.listFiles bdir))))))
        info    (fn [nm st]
                  (cond-> {:name nm}
                    st (assoc :head   (:id (last (store/deltas st)))
                              :deltas (count (store/deltas st)))))]
    {:current  branch
     :branches (vec (concat
                     [(info branch store)]
                     (for [[nm line] (sort-by key lines)]
                       (info nm (:store line)))
                     (for [nm (sort (remove (set (conj (keys lines) branch))
                                            (or on-disk [])))]
                       {:name nm})))}))

(defn build!
  "C1/C6 explicit build: materialize a runnable project under `dir` —
  `src/<ns-path>.clj` per namespace plus a minimal `deps.edn` (F8). Guarded
  (X4: an eval agent once built into the host repo, clobbering its deps.edn):
  absolute paths only, never a directory enclosing the running process, and a
  deps.edn this build didn't generate is never overwritten.

  With `:main` (a qualified entry fn, e.g. 'calc.core/run-cli) also emits the
  native-binary recipe (O4): a generated gen-class launcher at
  src/native/main.clj, a `:native` deps alias, and an executable
  build-native.sh that GraalVM-compiles the project to a self-contained
  binary `:name` (default: the entry ns's first segment)."
  [session dir & {:keys [main] bin-name :name}]
  (let [f        (io/file dir)
        target   (.getCanonicalFile f)
        cwd      (.getCanonicalFile (io/file "."))
        st       (:store @session)
        de       (io/file target "deps.edn")
        ;; a deps.edn is ours iff it's byte-identical to a generated variant
        ours?    #(contains? #{(build/deps-edn false) (build/deps-edn true)}
                             (slurp de))
        entry-ns (some-> main namespace symbol)]
    (cond
      (not (.isAbsolute f))
      {:error "build needs an ABSOLUTE directory path"}

      (.startsWith (.toPath cwd) (.toPath target))
      {:error (str "refusing to build into " target
                   " — it contains the running system")}

      (and main (nil? entry-ns))
      {:error (str ":main must be a qualified entry fn (ns/name), got " main)}

      (and main (nil? (store/form-named st entry-ns (symbol (name main)))))
      {:error (str "no form named " (name main) " in " entry-ns)}

      (and main (get-in st [:namespaces 'native.main]))
      {:error "a store namespace named native.main collides with the generated launcher"}

      (and main (.exists de) (not (ours?)))
      {:error (str target "/deps.edn exists and wasn't generated by build! — "
                   "the native recipe must own it; build into a fresh directory")}

      :else
      (do (doseq [ns-sym (keys (:namespaces st))]
            (let [file (io/file target "src" (render/ns-path ns-sym))]
              (io/make-parents file)
              (spit file (render/render-ns st ns-sym))))
          (when (or main (not (.exists de)))
            (spit de (build/deps-edn (boolean main))))
          (cond-> {:built (str target)}
            main
            (assoc :native
                   (let [an    (index/analyze (render/render-ns st entry-ns))
                         vdef  (first (filter #(and (= entry-ns (:ns %))
                                                    (= (symbol (name main)) (:name %)))
                                              (:var-definitions an)))
                         bin   (or bin-name (first (str/split (str entry-ns) #"\.")))
                         launcher (io/file target "src" "native" "main.clj")
                         script   (io/file target "build-native.sh")]
                     (io/make-parents launcher)
                     (spit launcher (build/launcher-source main (build/arg-style vdef)))
                     (spit script (build/native-script bin))
                     (.setExecutable script true false)
                     {:binary bin
                      :launcher "src/native/main.clj"
                      :script   "build-native.sh"})))))))
