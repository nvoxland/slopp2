# Run history

| date | model | setup | true tokens | duration | tool calls | outcome |
|---|---|---|---|---|---|---|
| 2026-07-02 | haiku  | files (conventional) | 31,475 | 131s | 27 | green, 2 cycles |
| 2026-07-02 | sonnet | files (conventional; used nREPL per inherited user config — flatters wall) | 45,627 | 250s | 29 | green, 6 cycles |
| 2026-07-02 | opus   | files (conventional) | 26,374 | 154s | 13 | green, 2 cycles |
| 2026-07-02 | haiku  | slopp@c0364be | 41,410 | 363s | 53 | green; 12 redundant test_runs |
| 2026-07-02 | sonnet | slopp@c0364be | 68,156 | 582s | 60 | green; extract/group/rename/move all used; ~1/3 calls burned on arg-schema guessing (since fixed) |
| 2026-07-02 | opus   | slopp@c0364be | 52,151 | 497s | 37 | green; red-first per function |
