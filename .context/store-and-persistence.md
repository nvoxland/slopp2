# Store & persistence

## In-memory model (`slopp.store` — pure functions over values)

- A namespace = ordered vector of **elements**: `{:id :kind :form :name :node}`
  for semantic forms, `{:kind :sep :node}` for whitespace/comment trivia kept
  only so rendering is lossless. `:node` is a rewrite-clj CST node.
- `:name` is derived from `def-heads` (`def/defn/.../deftest/ns`); nil for
  anonymous top-levels. **`deftest` is a named form on purpose** (tests are
  addressable/editable).
- Ids: `"f<n>"` forms, `"d<n>"` deltas, single monotonic counter (`:next-id`).
  The DB has a UNIQUE constraint on delta ids as a collision backstop.
- Deltas: `{:id :parent :op :ns :prompt ...}` — ops today: `:ingest`,
  `:replace`, `:add`, `:delete`, `:rename` (multi-form, `:form-ids`),
  `:verify` (result attached). `:parent` = previous delta id (linear now,
  DAG-ready).
- Multi-form coordinated edits go through `apply-changeset` → ONE delta over
  N forms (used by rename).
- **Purity is load-bearing:** transactional/atomic behaviors at the api layer
  (e.g. group validation) work by applying store fns to a value and only
  committing the result on success.

## Persistence (`slopp.db`, decision C7)

- SQLite at `<dir>/.slopp/store.db`, WAL mode. Tables:
  - `deltas(seq, id UNIQUE, op, ns, payload)` — append-only log; everything
    except id/op/ns lives in the EDN `payload` column (exact-reconstruction
    rule: `(merge {:id :op :ns} (edn/read-string payload)) = original`).
  - `elements(ns, pos, kind, form_id, name, source)` — materialized current
    state; `source` is the CST's canonical serialization (re-parsed on load;
    must reparse to exactly ONE node — asserted).
  - `meta(k,v)` — `next-id`.
- `persist!` = ONE transaction: delta row + full element rows of the touched
  namespace(s) (multi-ns arity for cross-ns ops like rename) + counter.
  Namespaces are small; full-ns row rewrite keeps write-through trivially
  correct.
- `load-store` reconstructs the entire in-memory store (returns nil if empty).
  `api/open! {:dir ...}` loads it AND replays every namespace into a fresh
  image.

## Rendering (`slopp.render`)

- `render-ns` = concat of element node strings — byte-exact round trip with
  ingestion (tested over a corpus; keep it that way).
- `element-offsets` = each element's [row col] start within the rendered
  source. This is the bridge from clj-kondo positions to store elements —
  rename correctness depends on it.

## Gotchas

- Delta payloads must stay plain EDN data (no CST nodes, no objects).
- If you add a delta op with a new key, nothing else is needed for
  persistence (payload column is schemaless) — but decide whether
  `query-lineage` should match it (it matches `:form-id` and `:form-ids`).
- `.slopp/` is gitignored; what users commit to VCS is an open Phase-4
  question (the delta DAG is meant to BE the history).
