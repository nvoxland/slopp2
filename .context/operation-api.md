# Operation API (`slopp.api` + `slopp.mcp`)

## Session

An atom: `{:store <value> :image <handle> :db <conn|nil> :test-map {...}
:warm-spare? bool :spare <future|nil>}`.
- `open!` → ephemeral; `open! {:dir d}` → durable (loads store AND replays
  namespaces into the image); `{:warm-spare? true}` for cheap restarts (the
  MCP server sets it).
- `close!` stops image + spare + db. Never leak child JVMs.

## Read surface (form-addressed; never file+line)

`query-source` (VFS render) · `query-symbol` (id, name, `:effectful?`,
source) · `query-references` · `query-lineage` (deltas matching `:form-id`
or membership in `:form-ids`) · `query-eval` (**observe-only** oracle access —
by convention it must not redefine code; redefinition belongs to edit ops).

## Write surface (each = tracked delta(s) + hot-reload + verification + provenance)

- `ingest!` — load a whole namespace from source; returns `{:ns :forms}` or
  `{:error}` (never throws on bad source).
- `create-ns!` — first-class new-namespace op (optional `:requires` clause
  strings); `add-require!` — structural, dup-checked require addition through
  the replace pipeline. Prefer these over hand-ingesting/replacing ns forms.
- `edit-replace!` — whole-form replace (O1); the common "semantic patch" path.
- `add-form!` / `delete-form!` — grow/shrink a namespace (delete `ns-unmap`s).
- `rename!` — coordinated multi-form rename; see `slopp.refactor` notes below.
- `edit-group!` — several `:replace`/`:add`/`:delete` steps as ONE atomic
  intent (F2): all steps apply to a store VALUE first (any error → whole group
  rejected, nothing committed — store purity makes this free), then commit +
  persist + hot-reload together and verify ONCE. Deltas share a `:group` id.
  Use for every multi-form refactor — it avoids the mid-refactor red + wasted
  diagnostic restart.
- `test-run!` — traced+diagnosed run; `ns-sym` nil = the WHOLE project in
  one image eval (instrumentation paid once — F-3c1); refreshes the trace
  map. `query-eval` surfaces evaluation errors as `{:error msg}` (F-3c2);
  `query-references` scans every namespace (F-3c3).
- `checkpoint!` — unit-of-work boundary (user-designed): deterministically
  normalizes every form changed since the last checkpoint (`slopp.normalize`,
  conservative kibit-style rules, node-level so inner formatting survives),
  commits ONE `:normalize` group delta, hot-reloads + re-verifies affected
  tests, records a labeled `:checkpoint` delta. Never rewrites silently
  mid-edit — only at this explicit call. Add rules deliberately (they must be
  provably behavior-preserving) and note them in the normalize ns.
- `restart!` — agent-callable fresh image (D5 escape hatch).
- `build!` — materialize `.clj` files (the C1/C6 explicit build). With
  `:main` (qualified entry fn) it also emits the O4 native-binary recipe:
  a generated launcher, a `:native` deps alias, and `build-native.sh`
  (user runs it; needs GraalVM 21+ on PATH). Generators live in
  `slopp.build`; X4 guards apply, plus: a deps.edn the build didn't
  generate is never overwritten.

Every edit ends with `run-verification!` (affected-narrowed, diagnosed) and a
`:verify` delta. Result shape: `{:delta :warnings :test :affected}` +
`{:error msg}` on validation failure. **Keep return shapes tidy maps** —
every op, `ingest!` included (`{:ns :forms}` / `{:error}`), returns one (F8).

## Concurrency (item 4 — CRDT-aligned, no locks)

Single-form writes (replace/add/delete/move) commit through
`rebased-write!`: the pure store transform runs INSIDE `swap!`, so concurrent
DIFFERENT-form writes rebase and all land (the granularity dodge, made real);
if the target form itself changed since the op began → `{:conflict ...}`
(C5's MV-register semantics, Phase-1 face). The compile gate runs once before
commit (form content is invariant across rebases). Multi-form ops
(group/rename/extract/checkpoint) guard with conflict-on-contention rather
than rebasing. Persistence is ORDERED via a per-session agent
(`persist-async!` — element rows derive from the current store at execution
time, so they never regress); `close!` awaits the queue. The image needs no
locking: all image work rides ONE nREPL session (per-eval serialization) and
`traced-run`/hot-loads are single evals — keep multi-step image work inside
one eval. `*pre-commit-hook*` is the deterministic test seam.

## `slopp.refactor` (rename mechanics)

Position-based: clj-kondo gives resolved sites; **use `:name-row`/`:name-col`
for usages** (`:row`/`:col` point at the CALL's paren, not the symbol —
learned the hard way). Sites → owning element via `render/element-offsets` →
element-local positions → rewrite-clj position-tracked zipper replaces exactly
those tokens (descending order so positions stay valid). Shadowed locals are
never touched because kondo never reports them as var usages.
**Limitation:** symbols inside `:refer` vectors aren't var-usages → not
rewritten.

## Transports

Two transports share the SAME dispatch (`mcp/handle`):
- **MCP stdio** (`clojure -M -m slopp.mcp [dir]`) — Claude Code (`.mcp.json`
  in-repo) and Codex (`config.toml` recipe in README). Optional dir = durable
  session.
- **HTTP** (`clojure -M -m slopp.http <port> [dir]`, or
  `http/start-server!` programmatically) — localhost-only JSON for
  curl/scripting/evals; `/metrics` returns per-call payload sizes.

## MCP transport (`slopp.mcp`)

- Minimal JSON-RPC 2.0 over newline-delimited stdio; pure `handle` dispatch
  (testable with plain maps) + `serve!` loop. Entry:
  `clojure -M -m slopp.mcp`.
- Tool names use underscores (MCP name charset). Tool results = `pr-str`'d
  tidy maps in one text content block; tool exceptions → `isError` result,
  protocol errors → JSON-RPC errors.
- When adding an api op, add: tool schema + `call-tool` case + (usually) a
  `select-keys` whitelist of the result.
