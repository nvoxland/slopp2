# Decision log

Settled decisions. Don't re-litigate silently — revisit explicitly and record
the change here (same commit).

## D — dialect & verification philosophy

- **D1 — No `@examples` / "deterministic choke point".** Old slopp's mechanism
  presumed an untrusted black-box compile step; slopp2's agent authors real,
  readable forms with a live oracle. Verification = tests + REPL observation.
  Form-granularity comes from **runtime tracing** (which forms did each test
  exercise), not co-located examples.
- **D2 — Contracts are an optional, library-agnostic boundary tool.** Shape vs.
  behavior are different lanes; tests+REPL own behavior/requirements. Nothing
  contract-library-specific is built in (no Malli/spec coupling); never
  enforced by the system, anywhere. Lean INTO Clojure's data dynamism — the
  live oracle is what makes that safe for a limited-context agent.
- **D3 — Dialect = allow-by-default with a denylist** (analysis defeaters:
  `eval`, `alter-var-root`, `binding`, `gen-class`, `definline`,
  `read-string`). Keep data dynamism; constrain metaprogramming dynamism.
- **D4 — User macros banned** (`defmacro` rejected). Built-in macros fine;
  runtime `macroexpand` remains the oracle for those.
- **D5 — No purity rule; refresh-vs-restart on an owned process.** Refresh is
  the fast path; restart = always-faithful backstop. Warm spare keeps restarts
  off the critical path. Detection is sampling; external side effects are out
  of scope.
- **D5.1 (user-flagged) — Smart red diagnosis, not restart-on-every-red.**
  A red cross-checks on a fresh image ONLY when staleness is plausible:
  (a) reload-signature failures (unbound var / unbound fn / no protocol impl /
  same-named-class CCE); (b) an unexplained flip — a failing test whose traced
  form-set doesn't intersect the just-edited forms (also catches value-capture
  staleness, since captured calls bypass the trace); (c) missing trace info or
  truncated failures. Otherwise `{:diagnosis :genuine}` — one run, no restart.
  Compile-gate failures heal the same way: refresh + one retry (`:image-healed`).
  `test_run {:fresh true}` forces a faithful single run; `restart` remains.
- **P1 — The oracle stays OUT-of-process (asked and answered).** Subprocess
  isolation is load-bearing for agent-generated code: guaranteed kills for
  runaway/OOM evals, `System/exit` containment (no SecurityManager on 21+),
  and D5's "fresh process = faithful by construction" purity (classloaders
  leak statics/hooks/natives). Loopback nREPL RTT is not a measured cost;
  spawn cost is amortized by the warm spare. Revisit only if we ever want
  fleets of parallel throwaway read-only oracles (isolated-classloader mode).
- **D6 — `!` naming enforced as a static effect-marker.** A fn must be
  `!`-named iff it transitively reaches an effectful leaf (call-graph
  propagation via clj-kondo; sound for first-order code; HOFs are the known
  leak, covered by runtime observation). Scope = **modification** (in-process
  mutation + external writes), NOT reads/non-determinism. Open question F7:
  stdout (`println`) is currently unflagged — matches Clojure convention, but
  needs an explicit scope call.

## C — storage core

- **C1 — Purely virtual: no on-disk `.clj` by default.** VFS renders from the
  store; explicit `build!` materializes. No reconciliation loop exists.
- **C2 — Identity = opaque synthetic stable ids** (survive rename/edit;
  monotonic counter now, globally-unique ids when multi-agent arrives).
- **C3 — Form-version value = rewrite-clj CST**; canonical serialization is
  the source text (lossless re-parse).
- **C4 — Delta-log-first**: event-sourced log now; concurrent-merge CRDT
  algorithm deferred to Phase 4.
- **C5 — Same-form concurrency = MV-register** (surface conflicts), Phase 4.
- **C6 — External tools: in-process + explicit build; no FUSE dependency.**
- **C7 — Persistence = SQLite** (`.slopp/store.db`, WAL, one tx per mutation:
  delta row + touched namespaces' element rows + id counter). EDN remains the
  value representation (delta payload column). The store IS the source code —
  it gets a real storage engine, not hand-rolled EDN files.

## O — operation API

- **O1 — Write model = whole-form replace** + structural ops layered
  (rename; extract/inline/move later). No sub-form patch language.
- **O2 — Edits auto-run affected tests** (trace-map narrowed; conservative
  full-ns fallback), result recorded on the delta.
- **O3 — Query = static index + runtime oracle from day one** (`query-eval`).
- **O4 — Native-binary build target.** `build!` with `:main` emits a GraalVM
  native-image recipe alongside the sources: a generated gen-class launcher
  (`src/native/main.clj`), a `:native` deps alias (graal-build-time +
  direct linking), and an executable `build-native.sh`. The compile itself
  stays an explicit user-run step — slopp never shells out to GraalVM. The
  launcher's `gen-class` is host-generated scaffolding, NOT authored store
  code, so it sits outside the D3 gate (same standing as `slopp.rt`'s
  instrumentation). The dialect is what makes the target reliable: D3/D4's
  bans (eval, read-string, gen-class, user macros) are exactly native-image's
  closed-world assumptions. Launcher arg passing is arity-aware via the
  index: a single fixed arity of 1 receives the CLI args as one vector;
  anything else is `apply`'d -main style.

## P4 — Phase 4 (multi-agent)

- **P4-m1 — Shared-session multi-agent = the first Phase-4 face.** One
  server process owns THE session (store + image + db); N agents connect via
  native MCP over streamable HTTP (`POST /mcp`, single-JSON responses,
  notifications → 202). Concurrency safety is the item-4 substrate (atomic
  rebasing commits; same-form → surfaced conflict; ordered persistence).
  Every write accepts an optional `:agent` recorded on its delta —
  provenance is per-agent from here on. Two separate server PROCESSES on one
  store.db remain unsupported (divergent in-memory stores); that's what
  fork/merge (m2) and replica sync (m3, deferred) are for.

- **P4-m2 — Fork = a copied project dir; merge = delta-log replay
  (C4/C5 activated).** `store/merge-logs` replays theirs' suffix onto ours,
  form-id-keyed: different-form work lands (granularity dodge across
  replicas); identical changes converge silently (⇒ merge is idempotent);
  same-form divergence = MV conflict — ours kept, theirs surfaced in
  `:conflicts` and on the `:merge` delta, resolved by hand. Add/add id
  collisions remap to fresh ids (fork-point detection compares full delta
  VALUES — both sides allocate the same next id for different work).
  Changeset ops (rename/normalize) apply all-or-conflict; `:move` skips with
  a note. `api/merge!` owns image loads (new nses in dep order, then changed
  forms through the compile gate) + whole-touched-nses verification + ONE
  `:merge` provenance delta. Globally-unique ids (C2's uuid/lamport) remain
  deferred — remap suffices for dir-forks.
- **P4-m2.1 — Iterated merges are exact via causal delivery (user-probed).**
  Replayed deltas are re-minted with OUR ids, so without bookkeeping a
  continuing fork's round-2 work false-conflicted ("both sides edited") —
  our copy of THEIR round-1 work looked like ours. Now every replayed delta
  carries `:merged-from <their-delta-id>` and the `:merge` delta records
  `:applied [their-ids]`: delivered deltas never replay again, and
  `:merged-from` deltas are excluded from the ours-touched conflict set.
  Fork → merge → keep forking → merge again is a supported loop; genuine
  same-form conflicts still fire. (Conflicted deltas are NOT marked
  delivered — they resurface until resolved or content-converged.)

- **P4-m3 — Branches within one repo (user-requested).** A branch is an O(1)
  snapshot of the store value, sharing the delta-log prefix by construction —
  so `branch_merge` IS the m2 engine, causal delivery included. The single
  live image gets CHECKOUT semantics: `branch_switch` swaps the store and
  reloads only namespaces whose source differs (removed ns → fresh image);
  the trace map resets across lines. Durable sessions persist each branch as
  its own mini store-db under `.slopp/branches/<name>/` (full snapshot on
  create, normal write-through while active, lazy load on switch; survives
  restart). Merge direction: into the CURRENT line (checkout main to merge
  down); the branch survives and can continue. Multi-agent note: one active
  checkout per session — concurrent multi-line work wants forks (or an image
  pool, deferred).
- **P4-m3.1 — Cross-merge id-maps persist, scoped per source.** Found by the
  m3 tests: merge #1 remaps a branch's added form to a fresh id, and without
  remembering that mapping, merge #2 resolves the branch's follow-up edit
  against the WRONG form (mainline's same-numbered add) — silent corruption,
  not just false conflicts. The `:merge` delta now records `:id-map` next to
  `:applied`, and BOTH are scoped by `:from` (different sources mint
  colliding delta AND form ids). merge-logs takes `:from`.

- **P4-m4 — Line-owned images: park / adopt / reap (user-proposed).** Each
  branch line owns its image. Switching away PARKS the outgoing line's image
  intact (REPL state included — safe because inactive lines are immutable,
  so a parked image stays in step with its parked store by construction);
  switching back ADOPTS it (instant, same process); a line with no image
  BOOTS one on demand (warm spare applies). Parked images retire after
  `:branch-image-ttl-ms` idle (default 10 min; per-session daemon Timer
  calls `reap-idle-images!`, also callable directly); `branch_delete` and
  `close!` stop them outright. This replaced m3's diff-reload checkout
  wholesale — images are never mutated to match a line, they belong to one.
  P1 economics respected: JVMs are spun up on demand and reaped, not held
  per branch forever.

- **P4-m5a — Storage inversion: the db is the journal of record
  (user-directed re-architecture).** Toward per-agent servers on shared
  storage: durable commits are now JOURNAL-FIRST — `db/append!` lands the
  new deltas + touched element rows + id counter in ONE conditional
  transaction iff the head still equals the commit's base; the in-memory
  store is a CACHE that only ever trails the journal (`refresh-cache!`
  advances it, never regresses). Losers refresh + rebase (same granularity
  dodge, arbitrated by SQLite's cross-process writer serialization — WAL +
  busy_timeout; "SQLite is single-process" was OUR in-memory-primary
  assumption, not SQLite's limit). Ephemeral sessions keep the
  starvation-free in-swap-transform commit. The async persist queue is
  DELETED — the append IS the persist. Next: m5b multi-process protocol
  (foreign-delta refresh into images), m5c per-agent servers with private
  checkouts via `.mcp.json` stdio.

- **P4-m5b/c — Per-agent servers on shared storage (the Phase-4 endgame,
  user-architected).** `db/data-version` detects foreign commits for one
  PRAGMA read; `sync-with-journal!` (called by the MCP dispatch before every
  tool) refreshes the cache, reloads changed namespaces into the LOCAL
  image, and invalidates touched trace entries — co-resident servers
  converge continuously while m5a's append-CAS arbitrates their writes.
  Checkouts are per-server state over shared branch storage: each agent's
  `.mcp.json`-spawned stdio server holds its own branch, image, and REPL
  runtime; `:data-version` is per-connection, so branch ops re-init it (the
  bug the m5c test caught). The shared-HTTP single-server mode (m1) remains
  for process-light sub-agent swarms. Deferred: incremental delta-suffix
  refresh (full load-store per sync is fine at current scale); branch-create
  races between servers.

- **P4-m5d — Branch identity = name + line-id (user-probed).** Branches
  carry a uuid `:line-id` minted at creation (on the branch's store value;
  persisted as a meta row; shown in `query_branches`), and merge causal
  state is scoped by `branch:<name>#<line-id>` — so deleting and recreating
  a branch name is a genuinely fresh identity, not an accident of the
  monotonic id counter. Fork DIRS can't mint identity (cp -r), so merge
  guards instead: a "delivered" delta whose CONTENT (source texts — the
  form-id keys are remapped on replay) doesn't match our `:merged-from` copy
  means the path was recreated over dead history → loud
  `{:error "merge identity mismatch ..."}` instead of silently swallowing
  the new fork's work. Same-branch multi-agent remains the default working
  mode; branches are opt-in isolation.

- **P4-m6 — Episodes: automatic per-agent work units between checkpoints
  (user-designed).** The "micro branch" need is met WITHOUT branches: an
  episode is DERIVED from the journal (no tagging) — an agent's deltas since
  its last checkpoint. Boundary rule: your own last checkpoint; an agent
  that never checkpointed inherits the last stable spot BEFORE its first
  activity (so pre-existing history is never "contested"). `query_changes`
  = net per-form :was/:now diffs + steps + red→green verification arc;
  `checkpoint {agent}` closes and normalizes ONLY that agent's episode
  (parallel sub-agents don't interfere); `episode_revert` rolls the episode
  back as ONE atomic verified group, SKIPPING forms other agents also
  touched (:skipped-shared — never stomped); `query_history {collapse:true}`
  reads history at episode grain. Chosen over literal auto-branches because:
  zero per-write cost, append-only provenance preserved (collapse is a VIEW,
  not a squash), and shared-line continuous sync semantics unchanged.
  Discipline: parallel sub-agents MUST carry distinct :agent labels (SKILL).
  Deferred: isolation-until-stable as an opt-in built on real branches.

- **P4-m6.1 — Turn trees: label paths + timestamps (user-probed).** slopp
  can't observe conversational turns (it sees tool calls); the parent
  agent's checkpoint-bounded episode is the turn proxy. Hierarchy comes from
  the LABEL CONVENTION `parent/child` on sub-agents (set once at spawn —
  already required for episode independence): the collapsed history nests a
  child episode under the parent episode whose span contains it (orphans
  stay top-level). Every delta now carries `:at` (epoch ms) for forensic
  time — shared prefixes stay value-identical (copied), and replayed deltas
  differing by :at is fine because causal delivery, not value-identity,
  governs iterated merges.

- **P4-m6.2 — Turns are explicit AND enforced (user-designed).**
  `turn_begin {agent, intent: <verbatim user ask>, user}` / `turn_end` record
  the ROOT intent as `:turn-begin`/`:turn-end` deltas; the collapsed history
  wraps the turn's episode tree in a `{:turn {:intent :user :episodes}}`
  bracket. Real servers (mcp/-main, http/-main set `:require-turns?`)
  REFUSE writes without an agent label + open turn — the compile-gate
  precedent: hard gates with teaching errors beat conventions. Sub-agents
  ride their root agent's turn (path labels); `checkpoint` is always allowed
  (it closes work). api-level sessions stay ungated (tests, seeding,
  scripts). The zero-ceremony path: Claude Code hooks run the one-shot
  `slopp.turn` CLI (UserPromptSubmit → begin with the verbatim prompt,
  Stop → end), appending markers OUT-OF-BAND to the journal — the agent's
  server absorbs them via m5b sync, so the model never has to relay its own
  instructions. A turn may end red — failed turns are history too.

- **P4-m7 — Commit points: the MILESTONE grain, purely in-journal
  (user-designed).** `commit_point {description, agent}` = the full
  checkpoint pipeline, then a `:commit` marker delta `{:description :target
  :status :agent :at}` — a named pointer at the just-checkpointed head; the
  better-controlled milestone above turn ends, episode ends, and plain
  checkpoints, per branch. GREEN-GATED: red verification refuses the
  milestone (the checkpoint still stands) unless `:force true`, which
  records `:status :red` honestly. `:target <past delta id>` = pure
  retroactive marker (status derived from the log's last `:verify`).
  Markers-as-deltas ride the journal, branch snapshots, and merge replay
  for free (`:commit` is a no-content op in `replay-delta`). Surfaces:
  `query_commits` (newest first; targets anchor query_changes from/to
  between-milestone diffs), COMMIT rows in the collapsed history
  (contains-searchable by description). **Git integration explicitly
  REJECTED for now** (user, 2026-07-04): a `git_export` projection
  (build! + one git commit per milestone + sha cross-link) was built and
  removed same-day — commit points are slopp-internal; whether/how to
  bridge to git remains an open question, to be driven by demand, not
  built ahead of it. *Revised same day by P4-m8 (user-driven demand): the
  bridge exists, but inverted — slopp SERVES the git protocol; commit
  points stay slopp-internal-first.*

- **P4-m8 — Git compatibility layer: serve the protocol, project the
  milestones (user-requested; explicit revision of P4-m7's rejection).**
  The user asked for a git-compatible surface: a standalone server any git
  client can talk to (branches + commits + history), push/pull over the
  regular git protocol, stable git-style ids as a COMPATIBILITY layer
  (native `d<n>` stays authoritative). Shape decisions:
  - **Grain: commit points = git commits.** Turns/episodes/checkpoints stay
    invisible to git ("just the commits come out").
  - **Ids are hashes, not mintable:** git clients re-hash every object — the
    id IS the content hash. Stability therefore comes from DETERMINISM:
    each native commit is a pure function of its marker delta. `git_map`
    (main store.db, keyed delta_id+fingerprint) additionally pins delta→sha
    at first projection; imported commits keep their pushed shas verbatim.
  - **Eager capture, lazy projection:** `commit_point` snapshots the
    rendered `:tree` (byte-exact, trivia intact, sorted-map, schemaless
    payload — no migration) into the `:commit` delta; JGit projection
    happens only in the git server, on demand (`slopp.git/ensure-projected!`
    before every refs advertisement). Keeps JGit out of every agent
    server's write path and makes concurrent projectors converge on
    identical shas with no coordination. Cost accepted: tens of KB of
    journal per milestone (rare top grain; delta-encoding is a recorded
    follow-on). Markers WITHOUT `:tree` (pre-m8 history, retroactive
    `:target`) backfill lossily (inter-form trivia isn't in deltas) — pinned
    at first projection, never recomputed.
  - **Journal order IS the chain:** a retroactive `:target` marker lands as
    the NEWEST git commit carrying the OLDER tree. Git mirrors the journal;
    it does not re-sort chronology.
  - **Zed lesson applied** (their libgit2→CLI arc): never partially
    reimplement git semantics — JGit owns all object/pack/wire format; and
    being the SERVER sidesteps auth entirely (the git client owns
    credentials/SSH/config). Server-only v1; slopp-as-client is a follow-on.
  - **Red pushes land honestly** (user-confirmed): a push that compiles but
    turns tests red is accepted and recorded `:status :red`; only compile
    failures reject. Matches the write-model (edits record, milestones
    gate).
  - Ordering invariant (crash-safe): journal marker → git objects
    (content-addressed, idempotent) → git_map row (INSERT OR IGNORE +
    read-back) → ref CAS. Every step derivable from the previous;
    `ensure-projected!` repairs interruptions.
  - **Import semantics (M3):** a push's NET old→new span lands as new-file
    ingests (dependency order) + ONE edit group; each incoming commit
    becomes a `:commit` marker (`:git-sha`, `:git-author`, `:target` = the
    group head) pinned to its ORIGINAL sha — intermediate content states
    live on the git side only, per the user's "single delta, preserve the
    commits" ask. Honest caveat: a push with new files is N ingest deltas +
    one group, not literally one delta. Converged re-pushes skip
    per-form; a crash between group and markers heals on re-push (forms
    converge → markers land), between markers and rows heals at the next
    projection (`:git-sha` repair). Git is a guest writer — the ambiguous
    cases REJECT with the reason on the pusher's terminal: non-`src/**.clj`
    paths, file deletions, ns-declaration changes (requires are
    structural), anonymous top-level forms, duplicate names, per-form
    staleness (slopp moved since the push's base → "fetch first"),
    non-fast-forward/creates/deletes (JGit-level). Form deletions WITHIN a
    file are legitimate `:delete` steps.
  - **Surfacing:** `query-commits` rows carry `:sha` once minted
    (`db/commit-shas` reads git_map read-only; ambiguous post-fork id
    collisions are omitted, never guessed; imported markers surface their
    `:git-sha` from birth).
  - **v1 limits (recorded, not accidental):** localhost-only, no auth; no
    branch creation/deletion/tags over push (git clients can't push to a
    store with zero milestones — the first milestone comes from slopp);
    form order and inter-form trivia normalize back to slopp's layout on
    round-trip; **`branch_merge` does not transfer milestones** —
    `merge-logs` routes `:commit` through the unknown-op skip-with-note
    (store.clj), so a merged branch's history surfaces in git only at the
    next main milestone.
  - **Follow-ons (demand-driven):** slopp-as-client (fetch/push to GitHub
    from slopp), auth, push-creates-branch, per-commit import groups
    (better query-changes spans at N× verification cost), file
    deletions/renames on import, ns-decl imports, protocol v2, `:tree`
    delta-encoding if journal growth ever matters, projecting `:merge`
    deltas as git merge commits.

- **SG — clj-surgeon-inspired structural ops (user-directed borrow).**
  Compared against realgenekim/clj-surgeon (stateless babashka file
  surgery): its outline/mv we had richer; its cross-repo `:ls-tree` and CLJC
  family are out of scope (multi-repo files; no cljs image). Borrowed the
  four missing OPS, each stronger here (gated+verified+recorded vs "run
  your tests after"): `query_deps` (transitive callee tree — the planning
  input), `fix_declares` (move defns above callers when safe, delete
  satisfied declares, skip mutual recursion), `ns_rename` (decl + requires +
  FQ refs across the store, rekey, elements purged for the old ns — append!
  now deletes rows for nses absent from the store), `edit_extract_ns`
  (namespace split: new ns with copied requires, remaining callers rewritten
  to alias-qualified calls, require added, moved forms removed — one atomic
  verified group; guards: moved set may not call what stays, no external
  referencers (v1)). Caller rewriting is symbol-mapping (zipper), not
  position-based: local shadowing of moved fn NAMES is the known v1 edge.
  remove-form accepts a string form-ID (anonymous forms like declares).

## H — host

- **H1 — slopp itself is Clojure/JVM** (same runtime as image + tooling; no
  serialization wall to the oracle; in-process clj-kondo/rewrite-clj). CRDT
  will be built in Clojure; **Rust FFI is a last-resort escape hatch only,
  never planned**. Distribution concerns → GraalVM/babashka later if needed.

## F — user-test findings (status)

F1 failure-details in results ✅ · F2 atomic edit groups ✅ (calculator bench
−49% wall) · F3 `{:error}` on unparseable source ✅ · F4 `create-ns!`/
`ns_create` ✅ · F5 `add-require!`/`ns_add_require` ✅ (structural, dup-checked)
· F6 VFS-mapped stack traces ✅ (nREPL load-file + row padding; frames cite the
exact lines `query-source` shows) · F7 ✅ **decided (user): `!` = mutation only**, per Clojure convention —
stdout/console IO is NOT a `!` trigger; if IO tracking ever matters it becomes
a separate `:effects` fact, never a naming rule · F8 ✅ (ingest tidy-return; `:untested`
flag on edits no test exercises; `build!` emits `src/` + minimal `deps.edn`).
Details: `projects/calculator/REPORT.md` (untracked) and `.context/dogfooding.md`.

## T — tasker user-test findings (round 2, through the MCP wire)

T1 ✅ deftests exempt from the `!` rule · T2 ✅ orientation queries
(`query_namespaces`, `query_outline`) · T3 ✅ edits report only NEW `!`
violations + `:existing-warnings` count · T4 ✅ ingest/ns-create load the image
FIRST and commit only on success — a failed require/compile returns `{:error}`
with no store/image drift · T5 ✅ `query_eval` is observe-only by construction
(`edit/observe-gate` rejects def/in-ns/ns-unmap/alter-var-root/...; calling
effectful fns remains allowed — that's observation) · (obs.) hot-editing a
`(def x (atom ...))` form resets its in-image state — tests re-seed so verify
is unaffected; D5's defonce-preservation opt covers it if it ever matters.
Details: `projects/tasker/REPORT.md` (untracked).

## S/E — symmetric-eval findings (fresh agents driving slopp per model)

S1 ✅ **every write must compile**: all hot-loads checked against the candidate
store before commit; forward refs rejected at write time ((declare) is the
mutual-recursion escape); partial group loads restore a fresh image. This
transformed weak-model runs (haiku: +114% vs Go → beat Go outright on
inventory). S2 ✅ `edit_move` (stylistic/structural reorder; `:move` delta) ·
✅ `ns_remove_require` + unknown-tool errors list available tools (agents
invented both names) · E1 ✅ edit_rename arg aliases + clear missing-arg
errors (every sonnet/opus run guessed name/to first) · E2 ✅ SKILL.md teaches
the two-write red-first TDD shape (fn+test in one group → honest red →
replace). Full data: `benchmarks/results.md` symmetric-eval sections.

## R — eval round 2 (modify-and-extend, seeded codebase)

**Honest result: files won at ~60-line scale for all models** (+32..98% tok,
2.3–3.2× wall). Cause ranking: batching (files cover clustered changes in 2–4
whole-file writes; slopp paid ~10–20 verified round trips), per-write
verification wall (kondo re-runs now memo-cached ✅), schema guessing (arg
aliases + validation messages ✅), redundant test_runs (SKILL guidance ✅).
Correctness/safety all held: rename flawless for every model, checkpoint lint
caught a real ordering mistake, zero wrong-behavior incidents. **Fork partially resolved — W1 (user decision):** whole-namespace batch
writes are allowed for BRAND-NEW namespaces only (never overwrite): `ingest`
is that path, now with the standard verified-write tail (side benefit: it
seeds the trace map, so narrowing works from the first edit). Deferred
verification / whole-ns overwrite remain off the table. The scale side of the
fork (10+-namespace eval, too big to read whole) is the next experiment. Data: benchmarks/results.md; report: projects/eval2/REPORT.md.

## X/N — eval round 3 (scale) findings

X2 ✅ rename hot-loads the renamed DEF first (hash-order destroyed cross-ns
renames) · X3 ✅ image loads follow `store/ns-dependency-order` (topological;
map-key order went hash past 8 nses and silently half-loaded images from
`open!`) and failures throw loudly · X4 ✅ `build!` guarded (absolute paths
only, never a dir enclosing the running process, never clobber an existing
deps.edn — an eval agent built into the host repo) · N1 ✅ `!`-named callees
count as effectful anchors (cross-ns effect propagation).
**Round-3b verdict (the crossover, measured):** at 12-ns scale, slopp beat
files on aggregate tokens (−9%) and tool calls (104 vs 155); sonnet −42%
tokens vs its files baseline; files' costs grew +54% avg with scale while
slopp's stayed flat-to-down. Full data: benchmarks/results.md,
projects/eval3/RUNS.md.

## B — benchmark/baseline findings

B1 ✅ **terse green responses** (from the Go-baseline comparison): MCP write
results compress to `{:ok true :delta id :tests {:ran n :pass n} :affected n}`
when green-and-quiet; full detail on :error / red / NEW warnings / :untested /
explicit `:verbose true`. Measured: output tokens −32–38% across all three
benchmark apps (calculator 906→590, inventory 502→311, wordstats 542→370).
