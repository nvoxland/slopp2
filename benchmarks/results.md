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
| 2026-07-02 | 520b41c | calculator | 2 | 11 | 1305 | 725 | 590 |
| 2026-07-02 | 520b41c | inventory | 1 | 7 | 84 | 345 | 311 |
| 2026-07-02 | 520b41c | wordstats | 1 | 8 | 1236 | 427 | 370 |

## Symmetric eval, wave 1: fresh agents driving SLOPP (calculator, 2026-07-02 @ 42d677e)

Same protocol as the Go baselines (fresh sub-agents, only SKILL.md + the spec),
but building through slopp over the HTTP transport. Payload = server-side
/metrics; true tokens/duration from the harness.

| model | workflow | true tokens | duration | tool calls | payload in | payload out | outcome |
|---|---|---|---|---|---|---|---|
| opus   | Go files | 18,687 | 85s  | 11 | 865  | 8    | clean |
| opus   | slopp    | 23,455 | 131s | 13 | 546  | 143  | clean, linear (11 calls, 0 red) |
| sonnet | Go files | 27,766 | 127s | 23 | 1112 | 200  | clean TDD |
| sonnet | slopp    | 50,974 | 423s | 43 | 2090 | 1958 | green, but fought S1+S2 |
| haiku  | Go files | 19,574 | 97s  | 14 | 770  | 73   | clean |
| haiku  | slopp    | 41,899 | 460s | 93 | 3499 | 2216 | green after heavy flailing (38 query_evals, 3 restarts) |

Honest read: on a tiny greenfield app, slopp costs MORE true tokens than files
today (opus +25%, sonnet +84%, haiku +114%). Opus's clean run shows the floor:
payload-in -37% vs its Go run, perfectly linear workflow, terse greens held.
The weaker-model blowups were dominated by two product defects the eval
surfaced (exactly what it was for):
- S1: hot-load of a non-compiling form is UNCHECKED -- commits to the store,
  image silently keeps/lacks the var, agent sees {:ok :ran 0} instead of red.
- S2: add_form only appends -> top-down authoring creates forward refs that
  break fresh loads; no reorder op (agents did delete+re-add dances).
Also: agents invented plausible tools (help, ns_remove_require) -- add the
symmetric ops or suggest nearest-tool in the unknown-tool error.
Waves 2-3 (inventory, wordstats) deferred until S1/S2 are fixed -- rerunning
known defects wastes runs.

## Symmetric eval, waves 2-3: post-S1/S2 fixes (inventory + wordstats, @ 903214c)

Same protocol; the compile-gate (S1), edit_move, ns_remove_require, and
tool-listing errors were in place. All six runs ended green with the rename
step verified.

| model | app | workflow | true tokens | duration | tool calls | payload in/out |
|---|---|---|---|---|---|---|
| haiku  | inventory | Go    | 19,543 | 82s  | 15 | 515/52 |
| haiku  | inventory | slopp | **18,878** | **56s** | **10** | 316/147 |
| sonnet | inventory | Go    | 24,746 | 89s  | 16 | 685/53 |
| sonnet | inventory | slopp | 30,137 | 145s | 20 | 864/948 |
| opus   | inventory | Go    | 18,483 | 91s  | 16 | 587/17 |
| opus   | inventory | slopp | 21,224 | 97s  | 16 | 357/155 |
| haiku  | wordstats | Go    | 25,654 | 132s | 24 | 2369/210 |
| haiku  | wordstats | slopp | 41,908 | 381s | 62 | 1955/1473 |
| sonnet | wordstats | Go    | 24,787 | 82s  | 15 | 1013/75 |
| sonnet | wordstats | slopp | 34,722 | 179s | 26 | 1058/1169 |
| opus   | wordstats | Go    | 18,625 | 85s  | 11 | 785/75 |
| opus   | wordstats | slopp | 22,266 | 128s | 17 | 440/278 |

Read:
- The S1 compile-gate transformed the weak-model experience: haiku went from
  +114% (wave 1) to BEATING its Go baseline outright on inventory (-3% tokens,
  -32% wall, fewest calls of any run). Overall slopp overhead fell from
  +25..114% (wave 1) to -3..+63% (waves 2-3).
- Remaining measured frictions, all fixable: (1) edit_rename arg-key guessing
  cost every sonnet/opus run retries ("no conversion to symbol" raw error);
  (2) red-first TDD fights the compile-gate -- agents stub-danced; the
  idiomatic answer ((declare f) -> test -> honest red) needs to be taught in
  SKILL.md; (3) fixed overhead: SKILL.md read + curl envelope ~2-3k tokens/run.
- haiku/wordstats remains the outlier (62 calls): weak-model thrash on the
  sort-by-descending logic, not a product defect (its Go run also took 24).
- Payload inputs: slopp lower in 5 of 6 runs (form-writes vs whole-file
  rewrites). Payload outputs remain higher (structured verification vs silent
  green) -- by design, and B1 keeps quiet greens small.

## Symmetric eval, wave 4: calculator RERUN post-fixes (@ 31a002b)

Same app as wave 1, all fixes in (compile-gate, rename aliases, TDD guidance).
All green.

| model | wave 1 (pre-fix) | wave 4 (post-fix) | Go baseline |
|---|---|---|---|
| haiku  | 41.9k tok / 460s / 93 calls | 31.7k / 241s / 29 | 19.6k / 97s / 14 |
| sonnet | 51.0k / 423s / 43 | 34.6k / 175s / 24 | 27.8k / 127s / 23 |
| opus   | 23.5k / 131s / 13 | 32.3k / 264s / 25 | 18.7k / 85s / 11 |

- The fixes bought haiku -24% tokens / -48% wall (calls 93->29) and sonnet
  -32% / -59%. Gaps to Go: haiku +114%->+62%, sonnet +84%->+25%.
- Opus regressed vs its own exceptionally clean wave-1 run (schema probing
  this time) -- it never hit S1/S2, so the fixes had nothing to fix, and
  single-run variance (est. +/-40%) dominates clean runs. n=1 rows are
  directional, not precise; treat trends across models/apps, not cells.
- Standing overhead vs files at this project size: SKILL.md read + curl
  envelope (~2-3k tokens) + slopp's richer verification outputs. The bet
  remains that these amortize/win on larger, longer-lived codebases where
  orientation, narrowing, rename-safety, and provenance compound -- tiny
  greenfield apps are the LEAST favorable terrain for slopp, and it already
  reaches parity-to-modest-overhead there.

## Eval round 2: MODIFY-AND-EXTEND, seeded ~16-form tasker (@ 507d0e2)

Six requirements (optional-arg change, cross-ns feature, extract, rename,
test updates) over an unfamiliar seeded codebase; slopp cohort vs
conventional-files cohort (same seed, same spec). All six runs green;
acceptance verified.

| model | workflow | true tokens | duration | tool calls |
|---|---|---|---|---|
| haiku  | files | 31,475 | 131s | 27 |
| haiku  | slopp | 41,410 | 363s | 53 |
| sonnet | files | 45,627 | 250s | 29 |
| sonnet | slopp | 68,156 | 582s | 60 |
| opus   | files | 26,374 | 154s | 13 |
| opus   | slopp | 52,151 | 497s | 37 |

HONEST RESULT: files won across all models at this scale (+32..98% tokens,
2.3-3.2x wall for slopp). The orientation-advantage prediction failed at
~60 lines / 3 namespaces: file agents read everything (~600 tok) and BATCHED
multiple spec items per file write (2-4 writes, 2 test cycles), while slopp
paid a verified round trip per form write, amplified by red-first-per-function
habits and by schema-guessing friction (edit_extract :form vs :source,
edit_group :action vs :op -- wrong keys gave internal errors, not validation
messages; sonnet burned ~1/3 of its calls there). haiku also ran test_run 12x
despite per-write verification.

What worked as designed: edit_rename (3 forms, zero manual call-site edits,
all models), edit_extract + edit_move used successfully, a checkpoint lint
caught a definition-order mistake (sonnet), restart+history available. The
losses are latency/economics + schema UX, not correctness -- slopp cohort had
zero wrong-behavior incidents.

Fixes queued from this round: analysis memo-cache (kondo re-runs dominate
per-write wall), write-op arg aliases + real validation messages, SKILL
guidance on batching groups. Deeper open question for the design: per-write
verification pricing vs batched intents at small scales (files' advantage
here was BATCHING, which edit_group already offers but agents underused).
Caveat: files-sonnet used an nREPL (inherited user config) instead of cold
`clojure -M` per cycle, flattering its wall time somewhat.

## Eval round 3: SCALE (12-ns orders domain, rush-handling task, @ 23670e4)

Files cohort: 3/3 acceptance pass — haiku 36.3k/192s/40, opus 47.2k/283s/43,
sonnet 79.2k/403s/72. Scale DID tax files (opus +79% vs its round-2 cost).

slopp cohort: sabotaged from minute zero by two scale-only product bugs the
round existed to flush out (all sessions opened against a HALF-LOADED image):
- X3: open!/fresh-image! load namespaces in map-key order; >8 namespaces =
  unordered hash map -> requires hit the no-classpath hole -> SIX namespaces
  silently absent from the image (and restart repeats the damage).
- X2: rename! hot-loads its changeset in hash-map key order -> callers can
  reload before the renamed def exists -> compile fail, destructive.
Outcomes against that: sonnet PASS (161.8k/1539s/121 — diagnosed + hand-
rehydrated the image), opus PASS (144.6k/1521s/87 — diagnosed both bugs),
haiku FAIL acceptance (78.6k/970s/137 — inlined five namespaces' logic into
process-order! to escape, losing the rush-shipping rule).

Verdict: round 3 is defect-dominated, not thesis-answering. Notable even so:
two of three slopp agents delivered correct cross-cutting results against a
broken image, with correct provenance; the files cohort's costs grew with
scale as predicted. Round 3b (rerun post-fix) is the real terrain test.

## Eval round 3b: scale RERUN on fixed slopp (@ b3b5dd4)

Same seed, same task, X2/X3/X4 fixed. 3/3 acceptance PASS (identical correct
rush math; haiku produced clean cross-ns code this time).

| model | files | slopp 3b | delta (slopp vs files) |
|---|---|---|---|
| haiku  | 36.3k / 192s / 40 | 48.7k / 437s / 66 | +34% tok |
| sonnet | 79.2k / 403s / 72 | 46.0k / 244s / 19 | **-42% tok, -39% wall, -74% calls** |
| opus   | 47.2k / 283s / 43 | 52.8k / 342s / 19 | +12% tok, -56% calls |

THE CROSSOVER, MEASURED:
- Aggregate at 12-ns scale: slopp 147.5k vs files 162.7k tokens (-9%), and
  104 vs 155 tool calls -- first scale where slopp wins overall.
- The gradients tell the real story. Files cost grew +54% avg from round-2
  scale to round-3 scale (read-based orientation taxes with size). slopp's
  cost was flat-to-DOWN across the same jump (sonnet 68k->46k) -- orientation
  via outline/references doesn't grow with codebase size.
- Workflow shape converged on the design's intent: opus did the whole task in
  TWO mutations (one cross-ns rename, one 8-form group); sonnet's rename
  propagated across 3 namespaces in one shot.
- haiku remains above its files baseline (weak-model overhead on the tool
  workflow), but passed acceptance with clean architecture -- vs FAILING with
  inlined spaghetti pre-fix.
New (minor) finding N1: effectful-vars doesn't propagate effects across
namespaces (a !-named callee in another ns should count as an effectful
anchor); process-order! showed :effectful? false.
