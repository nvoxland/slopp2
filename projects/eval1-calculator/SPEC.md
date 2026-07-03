# Benchmark: greenfield calculator through slopp (symmetric eval waves 1 & 4)

Fresh sub-agents build the calculator (same functional spec as
projects/baseline-go/SPEC.md app 1, Clojure-flavored: tokens are doubles +
operator keywords; run-cli fn verified via query_eval/with-out-str) THROUGH
slopp over the HTTP transport.

## Protocol
- One isolated slopp server + temp store per agent
  (slopp.http/start-server! <port> {:dir tmp :warm-spare? true}).
- Agent briefing: read skills/slopp/SKILL.md first; interact only via
  curl POST localhost:<port>/call.
- Done = test_run 0 fail/0 error covering required cases + labeled checkpoint.
- Metrics: harness usage (true tokens/duration/tool calls) + server /metrics
  (payload in/out per call).
