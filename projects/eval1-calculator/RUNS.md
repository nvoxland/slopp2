# Run history

| date | model | setup | true tokens | duration | tool calls | payload in/out | outcome |
|---|---|---|---|---|---|---|---|
| 2026-07-02 | haiku  | slopp@42d677e (pre S1/S2) | 41,899 | 460s | 93 | 3499/2216 | green; heavy flailing (38 query_evals, 3 restarts) |
| 2026-07-02 | sonnet | slopp@42d677e (pre S1/S2) | 50,974 | 423s | 43 | 2090/1958 | green; fought S1+S2 |
| 2026-07-02 | opus   | slopp@42d677e (pre S1/S2) | 23,455 | 131s | 13 | 546/143   | green; clean linear |
| 2026-07-02 | haiku  | slopp@31a002b (post-fix)  | 31,734 | 241s | 29 | —         | green |
| 2026-07-02 | sonnet | slopp@31a002b (post-fix)  | 34,618 | 175s | 24 | —         | green, clean TDD |
| 2026-07-02 | opus   | slopp@31a002b (post-fix)  | 32,251 | 264s | 25 | —         | green; schema probes (variance vs wave 1) |
