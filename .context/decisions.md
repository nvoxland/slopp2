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
−49% wall) · F3 `{:error}` on unparseable source (open) · F4 ns-create op
(open) · F5 ns-add-require op (open) · F6 form-mapped stack traces (open) ·
F7 stdout-vs-`!` scope decision (open — needs user) · F8 return-shape/
`:affected`/build-scaffold polish (open).
Details: `projects/calculator/REPORT.md` (untracked) and `.context/dogfooding.md`.
