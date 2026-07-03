#!/usr/bin/env bash
# Acceptance for the multi-Claude eval: run against a slopp server on the
# SHARED store (post-run). usage: ./accept.sh <port>   exit 0 iff all pass
set -u
PORT=${1:?usage: accept.sh <port>}
BASE="http://localhost:$PORT/call"
fail=0
call() { curl -s -X POST "$BASE" -H 'Content-Type: application/json' -d "$1"; }
check() { if echo "$3" | grep -q "$2"; then echo "PASS  $1"; else echo "FAIL  $1  ->  $3"; fail=1; fi; }

for ns in tasker.model tasker.store tasker.report; do
  check "tests green: $ns" ':fail 0, :error 0' \
    "$(call "{\"name\":\"test_run\",\"arguments\":{\"ns\":\"$ns\"}}")"
done

BODY=$(python3 <<'PY'
import json
code = r'''
(let [t  (tasker.model/make-task 1 "ship" :high 10)
      tt (-> t (tasker.model/add-tag "v2") (tasker.model/add-tag "urgent"))
      sn (tasker.model/snooze t 3)]
  {:tagged?      (tasker.model/tagged? tt "urgent")
   :untagged     (not (tasker.model/tagged? t "urgent"))
   :tag-line     (tasker.report/task-line tt)
   :snooze-due   (:due-day sn)
   :snooze-line  (tasker.report/task-line sn)
   :both-line    (tasker.report/task-line
                   (tasker.model/snooze tt 1))
   :overview     (first (clojure.string/split-lines
                          (tasker.report/overview [t sn] 12)))})
'''
print(json.dumps({"name": "query_eval", "arguments": {"code": code}}))
PY
)
R=$(call "$BODY")
check "alice: tagged? predicate"        ':tagged. true'      "$R"
check "alice: untagged is false"        ':untagged true'      "$R"
check "alice: tags in task-line"        '#urgent #v2'         "$R"
check "bob: snooze bumps :due"          ':snooze-due 13'      "$R"
check "bob: (snoozed) in task-line"     '(snoozed)'           "$R"
check "MERGED: one line shows BOTH"     '#urgent #v2.*(snoozed)\|(snoozed).*#urgent' "$R"
check "bob: overview counts snoozed"    '1 snoozed'           "$R"

H=$(call '{"name":"query_history","arguments":{"collapse":true,"limit":40}}')
check "turn brackets with verbatim intents" ':intent'         "$H"
check "alice turn recorded"             'alice'               "$H"
check "bob turn recorded"               'bob'                 "$H"
RAW=$(call '{"name":"query_history","arguments":{"limit":100}}')
check "alice merged her branch"         ':op :merge'          "$RAW"

exit $fail
