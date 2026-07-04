# Run history

Model IDs: every row in this file ran through the same harness aliases,
which at recording time (2026-07-03) resolve to:
`haiku` = claude-haiku-4-5-20251001 · `sonnet` = claude-sonnet-5 ·
`opus` = claude-opus-4-8. Future rows MUST state the exact model id (not
just the alias) in the model column — aliases drift as models update.
Acceptance: `./accept.sh <port>` (committed here) — the orchestrator runs
it against each cohort's finished store; agent self-reports are never
trusted.

| date | model | setup | true tokens | duration | tool calls | outcome |
|---|---|---|---|---|---|---|
| 2026-07-03 | haiku | files (conventional) | 36,344 | 192s | 40 | green, 3 cycles |
| 2026-07-03 | opus  | files (conventional) | 47,187 | 283s | 43 | green, 3 cycles (+79% vs its round-2 files run — scale hurts read-based orientation) |
| 2026-07-03 | sonnet | files (conventional; nREPL per inherited user config) | 79,229 | 403s | 72 | green; per-ns red/green TDD, 1 mandated full-suite cycle |
| 2026-07-03 | haiku  | slopp@23670e4 (X2/X3 active) | 78,624  | 970s  | 137 | FAIL acceptance: inlined 5 namespaces into process-order! ("compilation issues" = X3 half-loaded image), rush shipping not doubled in workflow, image inconsistent |
| 2026-07-03 | opus   | slopp@23670e4 (X2/X3 active) | 144,621 | 1521s | 87  | PASS; diagnosed X2 (rename load order) + X3 (restart load order), manually repaired image |
| 2026-07-03 | sonnet | slopp@23670e4 (X2/X3 active) | 161,831 | 1539s | 121 | PASS; found 6 namespaces never loaded (X3 at server open), rehydrated by hand, avoided edit_rename (X2) |
| 2026-07-03 | haiku  | slopp@b3b5dd4 (X2/X3/X4 fixed) | 48,684 | 437s | 66 | PASS acceptance; clean cross-ns code (no inlining this time) |
| 2026-07-03 | sonnet | slopp@b3b5dd4 (X2/X3/X4 fixed) | 45,977 | 244s | 19 | PASS; one-shot edit_rename across 3 nses; BEAT its files baseline (-42% tok, -39% wall, -74% calls) |
| 2026-07-03 | opus   | slopp@b3b5dd4 (X2/X3/X4 fixed) | 52,790 | 342s | 19 | PASS; TWO mutations total (1 rename + 1 group of 8 forms); ~parity with files (+12% tok) |
| 2026-07-03 | haiku  | slopp@fcdbe7d (items 0-5: D5.1, query_project/search, hints, subform) | 40,333 | 380s | 54 | PASS acceptance; "no friction" reported; -17% tok vs 3b |
| 2026-07-03 | sonnet | slopp@fcdbe7d (items 0-5) | 53,622 | 289s | 28 (61 slopp calls, curl-batched) | PASS; red/green per change; flagged query_references cross-ns gap |
| 2026-07-03 | opus   | slopp@fcdbe7d (items 0-5) | 59,787 | 514s | 32 | PASS; red-first TDD w/ predicted reds; flagged no project-wide test_run |
| 2026-07-03 | sonnet-3d (claude-sonnet-5) | slopp@00d870e, protocol v2 (turn gate + agent labels + friction line) | 50,888 | 267s | 22 | PASS 20/20; clean turn protocol; ZERO manual multi-site patterns (self-report + mine) |
| 2026-07-03 | haiku-3d (claude-haiku-4-5-20251001) | slopp@00d870e, protocol v2 | 44,480 | 318s | 44 | PASS 20/20; turn gate absorbed without friction; only friction: JSON escaping + own test refinement |

Protocol v2 note (3d): the harness wrapper now includes the enforced turn
protocol (turn_begin/turn_end + agent label on writes) and the
friction-report line from .context/dogfooding.md. accept.sh unchanged.
