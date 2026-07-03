# Benchmark: MULTI-CLAUDE — two real Claude Code instances, one store

The real-integration test of the Phase-4/5 stack: two headless Claude Code
processes, each spawning its OWN slopp MCP server (native stdio, no curl
bridge) against ONE shared store dir, with turn provenance fully automated
by hooks. Exercises: journal-arbitrated concurrent writes, continuous
cross-server sync, the enforced turn gate, branch → merge with a designed
same-form conflict, per-agent episodes, and the collapsed turn/episode
history as a scoreable artifact.

## Setup (reproduce)

1. Seed: `clojure -M -m slopp.evalseed` → copy `eval-templates/tasker` to
   `projects/eval-multi/store` (3 namespaces: tasker.model / store / report,
   all green).
2. Agent workspaces `agents/alice/`, `agents/bob/`: each has ONLY
   - `.mcp.json` → `bash -c "cd <slopp-repo> && clojure -M -m slopp.mcp <store-abs>"`
   - `.claude/settings.json` → UserPromptSubmit/Stop hooks running
     `clojure -M -m slopp.turn <store-abs> hook-begin|hook-end <agent>`
     (the VERBATIM prompt reaches the journal; the model never opens turns).
3. Launch (concurrently, from each workspace):
   `timeout 1500 claude -p "<prompt below>" --model <exact model id> \
      --mcp-config .mcp.json --strict-mcp-config \
      --allowedTools "mcp__slopp,Read" --output-format json`
4. Score: `./accept.sh <port>` against a server on the store; journal review
   (turn brackets, agents, merge delta); RUNS.md row with exact model ids.

## Task — verbatim prompt for ALICE

```
You are agent "alice" collaborating with another agent ("bob") on a SHARED
Clojure codebase through slopp MCP tools. Pass agent: "alice" on EVERY write
(writes are refused otherwise). Turn tracking is automated — never call
turn_begin/turn_end. First read the manual with the Read tool:
<slopp-repo>/skills/slopp/SKILL.md — then work ONLY through the slopp tools.
Do not read or write any other files.

YOUR FEATURE — task TAGS:
1. On main, add to tasker.model: (tagged? task tag) → true iff the task
   carries that tag (tasks may have a :tags set; default #{}).
2. Create a branch named "tags" (branch_create) and do the rest there:
   - tasker.model: (add-tag task tag) → task with tag added to :tags.
   - tasker.report/task-line: append " #<tag>" for each tag, sorted, e.g.
     "[ ] ship (high) #urgent #v2".
   - Tests for all new behavior; keep every existing test green.
3. Switch back to main (branch_switch) and MERGE your branch
   (branch_merge {name: "tags"}). If the merge reports :conflicts, integrate
   BOTH sides' behavior into the conflicted form(s) — bob is changing some of
   the same code on main; nobody's feature may be lost.
4. Verify everything is green on main (test_run with no ns), then
   checkpoint {label: "tags", agent: "alice"}.

Report at the end: what you did, any conflicts and how you resolved them,
and any friction with the tooling.
```

## Task — verbatim prompt for BOB

```
You are agent "bob" collaborating with another agent ("alice") on a SHARED
Clojure codebase through slopp MCP tools. Pass agent: "bob" on EVERY write
(writes are refused otherwise). Turn tracking is automated — never call
turn_begin/turn_end. First read the manual with the Read tool:
<slopp-repo>/skills/slopp/SKILL.md — then work ONLY through the slopp tools.
Do not read or write any other files.

YOUR FEATURE — task SNOOZING (work on main; alice works on a branch):
1. tasker.report/task-line FIRST: append " (snoozed)" when the task has
   :snoozed? true, e.g. "[ ] ship (high) (snoozed)".
2. tasker.model: (snooze task days) → task with :due bumped by days and
   :snoozed? true.
3. tasker.report/overview: header becomes
   "<n> tasks, <o> overdue, <s> snoozed".
4. Tests for all new behavior; keep every existing test green. Note: alice's
   work may appear in the codebase while you work — that is normal; build on
   the current state, never revert her changes.
5. Verify everything is green (test_run with no ns), then
   checkpoint {label: "snooze", agent: "bob"}.

Report at the end: what you did, anything surprising you observed in the
codebase while working, and any friction with the tooling.
```

## Measurement

Harness JSON output: tokens/duration/turns per agent. Store-side: /metrics
if served, journal deltas (`:agent`, `:at`). Acceptance: `./accept.sh`.
Models MUST be recorded as exact ids in RUNS.md.
