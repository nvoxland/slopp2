# Benchmark: SCALE modify-and-extend, seeded 12-ns orders domain (eval round 3)

Seeded, known-green 12-namespace order-processing system (~140 forms,
617 lines incl. deterministic padding) — regenerate identically with:
`clojure -M -m slopp.evalseed large` (writes eval-templates/orders +
eval-templates/orders-files). Cohort protocol identical to eval2 (slopp over
HTTP on store copies; files cohort instrumented, test command requires all 12
orders.* namespaces and run-all-tests #"orders\..*").

Purpose: the terrain test — codebase large enough that read-everything-and-
batch stops being free; orientation/narrowing/rename should differentiate.

## Task (verbatim, both cohorts) — RUSH-order handling
1. Orders gain an optional :rush? flag: make-order accepts an optional 4th
   argument (existing 3-arg calls keep working; defaults to false).
2. Rush orders ship at DOUBLE the normal shipping cost.
3. Rush orders get NO bulk discount (member discount still applies).
4. order-summary's header line ends with " [RUSH]" for rush orders.
5. process-order! must reflect all of the above and include :rush? in its
   result map.
6. Rename orders.money/mul-rate to scale-cents everywhere.
7. Add/extend tests covering the new behavior; every existing test stays
   green (adjust only where the rename requires).
Done: touched namespaces green (+ labeled checkpoint for the slopp cohort).

## Protocol (verbatim harness wrapper, slopp cohort)

Reproduce a run: copy `eval-templates/orders` to a fresh dir, serve it with
`clojure -M -m slopp.http <PORT> <dir>`, launch ONE fresh-context sub-agent
per model with exactly this prompt (only <PORT> substituted; task inserted
verbatim from the section above):

```
You are working on an existing Clojure order-processing codebase. The code
does NOT live in files — it lives in a "slopp" store that you access ONLY
through HTTP tool calls with curl.

PROTOCOL
- Every operation: POST http://localhost:<PORT>/call with JSON body
  {"name": "<tool>", "arguments": {...}}
- Example: curl -s -X POST http://localhost:<PORT>/call -H 'Content-Type:
  application/json' -d '{"name":"query_project","arguments":{}}'
- Clojure source inside JSON strings must be JSON-escaped (quotes, newlines).
- FIRST, read the user manual:
  /Users/nvoxland/src/nvoxland/slopp2/skills/slopp/SKILL.md (Read tool). It
  explains every tool and the efficient workflow.
- Do NOT read or modify ANY files in the repo or eval directories besides
  that one SKILL.md. Do not use git. The store behind the HTTP API is the
  only source of truth for the codebase.

THE TASK — RUSH-order handling:
<the 7 numbered requirements above, verbatim>

Done when: every touched namespace's tests are green AND you finish with a
labeled checkpoint.

At the end, report: what you changed, your final test status, and any
friction you hit with the tooling.
```

Measurement sources: true tokens + tool calls + wall from the agent
harness's usage accounting; server-side call mix from GET /metrics,
FILTERED to the agent's run window by timestamp (the orchestrator's
acceptance probes pollute the tail otherwise). Acceptance = `./accept.sh
<PORT>` (exit 0), run by the orchestrator. SKILL.md is versioned with the
slopp commit recorded in the RUNS row — checking out that commit reproduces
both the tool behavior and the manual the agent read.
