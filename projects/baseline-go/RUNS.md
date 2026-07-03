# Run history

| date | model | setup | app | true tokens | duration | tool calls | payload in/out | outcome |
|---|---|---|---|---|---|---|---|---|
| 2026-07-02 | haiku  | go (baseline) | calculator | 19,574 | 97s  | 14 | 770/73   | green, 5 cycles |
| 2026-07-02 | haiku  | go (baseline) | inventory  | 19,543 | 82s  | 15 | 515/52   | green, 3 cycles |
| 2026-07-02 | haiku  | go (baseline) | wordstats  | 25,654 | 132s | 24 | 2369/210 | green, 6 cycles (thrash) |
| 2026-07-02 | sonnet | go (baseline) | calculator | 27,766 | 127s | 23 | 1112/200 | green, red-first TDD |
| 2026-07-02 | sonnet | go (baseline) | inventory  | 24,746 | 89s  | 16 | 685/53   | green |
| 2026-07-02 | sonnet | go (baseline) | wordstats  | 24,787 | 82s  | 15 | 1013/75  | green |
| 2026-07-02 | opus   | go (baseline) | calculator | 18,687 | 85s  | 11 | 865/8    | green |
| 2026-07-02 | opus   | go (baseline) | inventory  | 18,483 | 91s  | 16 | 587/17   | green, 2 cycles |
| 2026-07-02 | opus   | go (baseline) | wordstats  | 18,625 | 85s  | 11 | 785/75   | green |
