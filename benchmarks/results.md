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
