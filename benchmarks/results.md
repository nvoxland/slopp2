# Benchmark history

Wall + token cost of building each sample app through the MCP surface
(`clojure -M -m slopp.benchmark`; see `.context/dogfooding.md`).
Rows are comparable only within the same script version (v).

| date | sha | app | v | steps | wall ms | tok in | tok out |
|---|---|---|---|---|---|---|---|
| 2026-07-02 | 9a0caa3 | calculator | 1 | 12 | 2568 | 746 | 1051 |
| 2026-07-02 | 9a0caa3 | inventory | 1 | 7 | 67 | 345 | 502 |
| 2026-07-02 | 9a0caa3 | wordstats | 1 | 8 | 1254 | 427 | 542 |
| 2026-07-02 | 66c30c0 | calculator | 2 | 11 | 1316 | 725 | 906 |
| 2026-07-02 | 66c30c0 | inventory | 1 | 7 | 63 | 345 | 502 |
| 2026-07-02 | 66c30c0 | wordstats | 1 | 8 | 1277 | 427 | 542 |
| 2026-07-02 | 6f42ec6 | calculator | 2 | 11 | 1360 | 725 | 911 |
| 2026-07-02 | 6f42ec6 | inventory | 1 | 7 | 66 | 345 | 508 |
| 2026-07-02 | 6f42ec6 | wordstats | 1 | 8 | 1363 | 427 | 547 |

## Conventional-workflow baselines (Go; one-time rows, 2026-07-02 @ 7cb99a5)

The same three apps built by FRESH sub-agents (no slopp context) with the
conventional files + `go test` workflow, one run per model. Payload metrics
via `measure_go_baseline.sh` (artifact-derived); true tokens/duration from the
harness. All nine finished green with renames verified. Caveats in
`.context/dogfooding.md` (agent wall time includes thinking; slopp script rows
are deterministic replays — compare workflow *shape*, not raw wall).

| model | app | wall s | payload tok in | payload tok out | test runs | true agent tokens | tool calls |
|---|---|---|---|---|---|---|---|
| haiku  | calculator | 84  | 770  | 73  | 5 | 19,574 | 14 |
| haiku  | inventory  | 60  | 515  | 52  | 3 | 19,543 | 15 |
| haiku  | wordstats  | 100 | 2369 | 210 | 6 | 25,654 | 24 |
| sonnet | calculator | 114 | 1112 | 200 | 7 | 27,766 | 23 |
| sonnet | inventory  | 76  | 685  | 53  | 3 | 24,746 | 16 |
| sonnet | wordstats  | 72  | 1013 | 75  | 3 | 24,787 | 15 |
| opus   | calculator | 73  | 865  | 8   | 3 | 18,687 | 11 |
| opus   | inventory  | 80  | 587  | 17  | 2 | 18,483 | 16 |
| opus   | wordstats  | 72  | 785  | 75  | 3 | 18,625 | 11 |

Read against the slopp script rows (calculator 725/906, inventory 345/502,
wordstats 427/542 payload tokens in/out):
- **Input payloads: slopp wins, and the gap tracks rewriting.** Form-granular
  writes beat whole-file writes — modestly on clean runs (inventory 345 vs
  515–685), dramatically when an agent thrashes (haiku wordstats 2369 vs 427,
  5.5x): every conventional fix pays for the whole file again.
- **Output payloads: slopp currently LOSES** (502–906 vs 8–210): every slopp
  write returns a full verification summary + delta + warnings, while a green
  `go test` says almost nothing. Insight B1: make green responses terse
  (detail only on red/warnings/explicit ask).
- **Models:** opus was the most efficient (fewest tools, ~18.6k tokens);
  sonnet the most thorough (red-first TDD, most verbose); haiku cheapest
  per token but flailed hardest (wordstats: 24 tool calls).
