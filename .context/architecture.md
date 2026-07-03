# Architecture

## The stance (don't violate without a recorded decision)

- **The top-level form is THE unit** — of editing, CRDT/storage, hot-reload,
  verification, and provenance. One atom all the way down. Anything that
  splits those units apart (file-based edits, sub-form patching, whole-project
  reloads) works against the thesis.
- **No `.clj` files on disk.** The store (delta log + materialized elements in
  SQLite) is the source code. A VFS renders source on demand (`query-source`);
  an explicit `build!` materializes real files only when asked. There is no
  file→store reconciliation, ever.
- **The system owns a persistent JVM image** (nREPL subprocess). It is the
  L3 semantic oracle: behavior/shape questions are answered by *observation*
  (eval, tracing) rather than static declaration. Refresh (hot redefine) is
  the fast path; restart to a fresh image is the always-correct backstop.
- **Agents address forms semantically** (`ns` + `name` or form id) — never
  file+line. Reads return exactly the requested form/answer, which is where
  the token win comes from.
- **Every write is a tracked delta** `{op, ns, prompt, parent, ...}` — the
  history IS the provenance ("who touched this, via which op, driven by which
  prompt"). Raw REPL eval may observe but never redefines code.

## Layer map (bottom-up)

| ns | Role |
|---|---|
| `slopp.store` | pure in-memory form store + delta log (elements = forms + separator trivia; synthetic stable ids) |
| `slopp.render` | VFS: store → source string (lossless); `element-offsets` maps positions back to elements |
| `slopp.db` | durability: SQLite `.slopp/store.db`, one ACID tx per mutation |
| `slopp.repl` | owned image subprocess: start!/eval!/stop!/restart!; injects `slopp.rt` |
| `slopp.rt` | runtime support *inside* the image: traced test runs + failure capture |
| `slopp.image` | store↔image bridge: load-ns! (marks `*loaded-libs*`), test runs |
| `slopp.index` | clj-kondo static index (content-fed, no disk): defs/refs/call graph, `!`-effect reachability |
| `slopp.refactor` | position-based structural rewrites (rename) |
| `slopp.edit` | the write pipeline: parse → dialect gate → store commit → hot-reload |
| `slopp.api` | agent-facing operations + verification orchestration (a session atom: store, image, db, trace map, warm spare) |
| `slopp.mcp` | JSON-RPC 2.0 stdio MCP server over `slopp.api` (`clojure -M -m slopp.mcp`) |
| `slopp.bench` / `slopp.benchmark` | token-vs-grep metric / sample-app build benchmark |

## Cross-cutting gotchas

- Store namespaces have **no classpath presence**; after `load-ns!` the ns is
  marked in `*loaded-libs*` so other store namespaces can `(:require ...)` it.
  Load order across namespaces is ingestion order (topological load is a known
  Phase-1 gap).
- The rendered source is the coordinate system: clj-kondo rows/cols are
  positions in `render-ns` output; `render/element-offsets` + owner mapping
  translate them to store elements (see `slopp.refactor`).
- Host language is Clojure/JVM by decision H1 (same runtime as the image and
  the tooling); the CRDT will be Clojure too — **no Rust planned**.
