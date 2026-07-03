# Roadmap (beyond dogfood findings)

The functional goal: an agent-native codebase that is *measurably* better to
author in than text files — fewer tokens to orient, faster verified loops,
trustworthy history. Ordered by leverage:

## 1. Become a real MCP server in an agent harness (product moment of truth)
Wire `slopp.mcp` into Claude Code as an installed MCP server and author through
it as *tools* (not REPL calls). This is the true product surface and the
near-zero-config install story — DESIGN.md §5 calls install friction the
single biggest risk. Includes: config recipe, session lifecycle over long
conversations, concurrent-client behavior, portability (drop the hardcoded
homebrew `clojure` path).

## 2. Orientation queries (the token thesis, part 2)
Reading is solved form-by-form; *orientation* isn't. Add: `query_outline`
(namespace map: names, arities, `!`-status, one-liners — clj-surgeon's `:ls`
lesson), expose the existing call graph as `query_graph`, and
`query_namespaces` (what exists?). Prediction from calculator: multi-namespace
projects make this the next friction leader.

## 3. Deepen the L3 oracle (cash the D2 promise)
"Ask the REPL" today = raw `query_eval`. Add first-class observation:
`query_observe` (call a fn / run a test while capturing arg+return shapes at
a target var — the dynamic-typing safety net D2 leans on), and a
`macroexpand` tool. Runtime answers to "what flows through here?" without
reading callers.

## 4. Phase-3 structural ops + behavior preservation
`extract-fn` next (highest value after rename), then inline/move/change-
signature. Verified by execution: snapshot outputs → transform → re-run →
diff (the DESIGN.md §7 oracle). Also the CODESTRUCT-style eval: rename/edit
correctness + token cost vs. a string-replace/grep baseline, once the op set
is broad enough to be worth publishing numbers for.

## 5. Semantic × history depth (the novel core)
Today's lineage is per-form and linear. Add: form-at-delta (time travel),
delta-log search ("which prompts touched auth?"), form history diffs,
was-green-at queries, `query_history` (the DAG with prompts). This is the
"semantic×history combination nobody has shipped" (DESIGN.md §5) — the moat.

## 6. Phase-4: multi-agent / branch / merge
The deferred CRDT half (C4/C5): concurrent sessions as peers, branch/merge
over the delta DAG, form-sequence CRDT algorithm, MV-register same-form
conflicts, globally-unique ids (drop the monotonic counter). In Clojure (H1).
Big; starts after the single-agent loop is polished under real use.

## Housekeeping (whenever touched)
Topological namespace load order on restart; defonce-preserving refresh
(D5's deferred perf opt); fold decisions back into `DESIGN.md` (or mark it
historical, `.context/` is authoritative); MCP request-level concurrency.
