# Run history

Model ids are exact (recorded at run time). Acceptance: ./accept.sh <port>
(orchestrator-run; 14 checks). Wall/turns/tokens from the claude -p harness
JSON (`usage` = fresh in/out; cache reads listed separately).

| date | agent | model | turns | wall | tokens in/out (cache-read) | outcome |
|---|---|---|---|---|---|---|
| 2026-07-03 | alice (tags: branch+merge) | claude-sonnet-5 | 45 | 165s | 4,687/9,835 (1.68M) | PASS; ONE designed conflict on task-line, integrated both features from the ours/theirs record, 22/22 green, checkpointed |
| 2026-07-03 | bob (snooze: main) | claude-sonnet-5 | 20 | 114s | 4,648/8,375 (0.81M) | PASS; observed alice's forms landing mid-session via sync ("handled transparently, never had to coordinate"); used query_changes to confirm episode scope; matched :due-day over the brief's :due |

Round notes (2026-07-03, slopp@5d3c48a):
- First REAL-CLIENT run ever: native stdio MCP (own server per agent via
  .mcp.json), hooks-automated turns (UserPromptSubmit/Stop -> slopp.turn),
  the enforced turn gate, cross-server journal sync, branch+merge -- all
  under two concurrent headless Claude Code instances. Zero integration
  failures.
- The MV conflict story validated end-to-end by a live model: alice called
  the ours/theirs payload "trivial to see exactly what needed integrating --
  no need to dig through file diffs or history".
- The collapsed history reads as designed: {:turn {verbatim intent,
  :episodes [{:label "tags" ...}]}} per agent.
- Spec-vs-codebase test passed incidentally: brief said :due, seed uses
  :due-day; bob read the source and matched the codebase, noting it.
- accept.sh needed two fixes during scoring (probe field :due -> :due-day
  mirroring the spec's own error; BSD-grep \? escape) -- the agents were
  right, the harness was wrong.
