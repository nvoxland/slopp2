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

- `ingest!` — create/replace a whole namespace from source (currently ALSO the
  only way to create a namespace — F4 open).
- `edit-replace!` — whole-form replace (O1); the common "semantic patch" path.
- `add-form!` / `delete-form!` — grow/shrink a namespace (delete `ns-unmap`s).
- `rename!` — coordinated multi-form rename; see `slopp.refactor` notes below.
- `test-run!` — full traced+diagnosed run; refreshes the trace map.
- `restart!` — agent-callable fresh image (D5 escape hatch).
- `build!` — materialize `.clj` files (the C1/C6 explicit build).

Every edit ends with `run-verification!` (affected-narrowed, diagnosed) and a
`:verify` delta. Result shape: `{:delta :warnings :test :affected}` +
`{:error msg}` on validation failure. **Keep return shapes tidy maps** —
`ingest!` still returns the session atom (F8, fix pending).

## `slopp.refactor` (rename mechanics)

Position-based: clj-kondo gives resolved sites; **use `:name-row`/`:name-col`
for usages** (`:row`/`:col` point at the CALL's paren, not the symbol —
learned the hard way). Sites → owning element via `render/element-offsets` →
element-local positions → rewrite-clj position-tracked zipper replaces exactly
those tokens (descending order so positions stay valid). Shadowed locals are
never touched because kondo never reports them as var usages.
**Limitation:** symbols inside `:refer` vectors aren't var-usages → not
rewritten.

## MCP transport (`slopp.mcp`)

- Minimal JSON-RPC 2.0 over newline-delimited stdio; pure `handle` dispatch
  (testable with plain maps) + `serve!` loop. Entry:
  `clojure -M -m slopp.mcp`.
- Tool names use underscores (MCP name charset). Tool results = `pr-str`'d
  tidy maps in one text content block; tool exceptions → `isError` result,
  protocol errors → JSON-RPC errors.
- When adding an api op, add: tool schema + `call-tool` case + (usually) a
  `select-keys` whitelist of the result.
