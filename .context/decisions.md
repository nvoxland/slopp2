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
  the fast path; restart = always-faithful backstop. **Restart-as-diagnostic**:
  never believe a red until it survives a fresh image (red→green = staleness
  healed; red→red = confirmed). Warm spare keeps restarts off the critical
  path. Detection is sampling; external side effects are out of scope.
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
