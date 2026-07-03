# Dogfooding & benchmarks

## User testing (standing practice)

Build real things **through slopp itself** — the agent is the target user, so
this is user testing in the literal sense.

- Work in `projects/<name>/` (gitignored; never check these in).
- Drive `slopp.api`/`slopp.mcp` the way an MCP agent would (form-addressed
  ops, one write at a time, read only what you need).
- Log honestly to `projects/<name>/REPORT.md`: what worked, friction ranked
  by severity, meta-observations. Findings become F-numbered roadmap items in
  `.context/decisions.md` + tasks.
- First session: CLI calculator (2026-07-02, `projects/calculator/REPORT.md`).
  Wins: tight add→verify loop, real affected-narrowing, honest diagnostics,
  trustworthy rename, story-like lineage, seamless persistence. Friction:
  F1–F8 (F1 fixed).

## Benchmark suite (`slopp.benchmark`)

Purpose: track whether the product is getting better to use — **wall time**
and **token cost** (chars/4 of the JSON actually sent/received through
`mcp/handle`) to build each sample app via scripted agent sessions.

- Run: `clojure -M -m slopp.benchmark` (NOT part of `clojure -M:test` — it
  spawns several JVMs and takes minutes).
- Each app = a deterministic script of MCP tool calls (deliberate red steps
  included — debugging is part of real usage). The run fails loudly if an
  app's final test run isn't green.
- Results append to `benchmarks/results.md` (committed — it's the progress
  record): git sha, app, steps, wall ms, tokens in/out.
- **Run it when the numbers should be changing** (user guidance) — edit-path /
  verification / restart changes, not routinely. Skip it for query additions,
  docs, or anything off the measured path.
- Scripts should exercise *current best practice* (e.g. once edit groups
  exist, the multi-form fix step uses them) — the benchmark measures the
  product as it's meant to be used. When a script changes, note it in the
  results row; wall/token comparisons are only valid between rows with the
  same script version.

## Conventional-workflow baselines (one-time rows)

The same three apps built in **Go by fresh sub-agents** (no context from the
slopp side), one run per model (haiku/sonnet/opus), conventional files +
`go test` workflow. Instrumentation is mechanical so metrics come from
artifacts, not self-reports: `git commit` after every write (tok-in = summed
byte sizes of changed `.go` files per commit), every test run tee'd to
`.runs/` (tok-out), `date +%s` stamps (wall). Measured by
`benchmarks/measure_go_baseline.sh`; recorded once per app×model with
`v = go-<model>`.

**Comparability caveats (keep honest):** Go wall time includes the agent's
thinking time; slopp script rows are deterministic replays (no thinking, no
model). Token metrics compare the *workflow shape* (whole-file writes + test
output reads vs. form writes + structured results) — that's the comparison
that matters. A fully symmetric eval (fresh agents driving slopp over MCP per
model) is roadmap #1 territory.
