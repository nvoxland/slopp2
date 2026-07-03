# Run history

| date | model | setup | true tokens | duration | tool calls | outcome |
|---|---|---|---|---|---|---|
| 2026-07-03 | haiku | files (conventional) | 36,344 | 192s | 40 | green, 3 cycles |
| 2026-07-03 | opus  | files (conventional) | 47,187 | 283s | 43 | green, 3 cycles (+79% vs its round-2 files run — scale hurts read-based orientation) |
| 2026-07-03 | sonnet | files (conventional; nREPL per inherited user config) | 79,229 | 403s | 72 | green; per-ns red/green TDD, 1 mandated full-suite cycle |
| 2026-07-03 | haiku  | slopp@23670e4 (X2/X3 active) | 78,624  | 970s  | 137 | FAIL acceptance: inlined 5 namespaces into process-order! ("compilation issues" = X3 half-loaded image), rush shipping not doubled in workflow, image inconsistent |
| 2026-07-03 | opus   | slopp@23670e4 (X2/X3 active) | 144,621 | 1521s | 87  | PASS; diagnosed X2 (rename load order) + X3 (restart load order), manually repaired image |
| 2026-07-03 | sonnet | slopp@23670e4 (X2/X3 active) | 161,831 | 1539s | 121 | PASS; found 6 namespaces never loaded (X3 at server open), rehydrated by hand, avoided edit_rename (X2) |
