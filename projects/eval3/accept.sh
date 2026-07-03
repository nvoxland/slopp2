#!/usr/bin/env bash
# Acceptance check for the eval3 RUSH-order task, run against a slopp HTTP
# server serving a cohort's finished store. The orchestrator runs this —
# never trust an agent's self-reported status.
#
#   usage: ./accept.sh <port>        exits 0 iff ALL checks pass
#
# Checks (must match SPEC.md requirements 1-7):
#   rename complete; shipping exactly 2x on rush; bulk discount dropped while
#   member discount kept; " [RUSH]" header on rush only; :rush? in the
#   process-order! result map; all 12 orders.* test namespaces green.
set -u
PORT=${1:?usage: accept.sh <port>}
BASE="http://localhost:$PORT/call"
fail=0

call() { curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d "$1"; }

check() { # label, required-regex, actual
  if echo "$3" | grep -q "$2"; then
    echo "PASS  $1"
  else
    echo "FAIL  $1  ->  $3"
    fail=1
  fi
}

check "rename: no mul-rate remains" '"result":"\[\]"' \
  "$(call '{"name":"query_search","arguments":{"pattern":"mul-rate"}}')"
check "rename: scale-cents in use" 'scale-cents' \
  "$(call '{"name":"query_search","arguments":{"pattern":"scale-cents"}}')"

# behavioral probe, built in python so no shell-quoting of Clojure strings
BODY=$(python3 <<'PY'
import json
code = r'''
(let [o      (fn [r?] (orders.entity/make-order 9
                        [(orders.entity/make-item :a 10 1000 500)] true r?))
      ship-n (orders.shipping/ship-cents (o false))
      ship-r (orders.shipping/ship-cents (o true))
      _      (orders.inventory/set-stock! :a 100)
      n      (orders.workflow/process-order! (o false) :mn)
      _      (orders.inventory/set-stock! :a 100)
      r      (orders.workflow/process-order! (o true) :mn)
      header (first (clojure.string/split-lines
                      (orders.report/order-summary (o true))))]
  {:ship2x       (= ship-r (* 2 ship-n))
   :flags        [(:rush? n) (:rush? r)]
   :member-kept  (pos? (:discount r))
   :bulk-dropped (< (:discount r) (:discount n))
   :rush-header  (clojure.string/ends-with? header " [RUSH]")
   :plain-header (not (clojure.string/includes?
                        (orders.report/order-summary (o false)) "RUSH"))})
'''
print(json.dumps({"name": "query_eval", "arguments": {"code": code}}))
PY
)
R=$(call "$BODY")
check "shipping exactly 2x on rush"     ':ship2x true'         "$R"
check ":rush? flags [false true]"       ':flags \[false true\]' "$R"
check "member discount kept on rush"    ':member-kept true'    "$R"
check "bulk discount dropped on rush"   ':bulk-dropped true'   "$R"
check "header ends with [RUSH] on rush" ':rush-header true'    "$R"
check "no RUSH marker on normal order"  ':plain-header true'   "$R"

for ns in catalog discount entity inventory money pricing report shipping \
          stats tax validate workflow; do
  check "tests green: orders.$ns" ':fail 0, :error 0' \
    "$(call "{\"name\":\"test_run\",\"arguments\":{\"ns\":\"orders.$ns\"}}")"
done

exit $fail
