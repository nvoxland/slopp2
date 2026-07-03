# Benchmark: modify-and-extend, seeded tasker (eval round 2)

Seeded, known-green 3-namespace tasker (model/store/report, ~16 forms) —
regenerate identically with: `clojure -M -m slopp.evalseed eval-templates/tasker`
(store) which also builds the files twin. slopp cohort works over HTTP servers
on copies of the store; files cohort works on copies of the built project
(instrumented like projects/baseline-go/SPEC.md, test command = clojure -M -e
requiring the three namespaces and running their tests).

## Task (verbatim, both cohorts)
1. Tasks gain optional tags (a set of keywords): make-task accepts an optional
   5th argument; existing 4-arg calls keep working; tags default to #{}.
2. task-line appends " #tag1 #tag2" (tags sorted by name) when the task has tags.
3. New fn tags-summary in tasker.report: given tasks, a map of
   tag -> count of NOT-done tasks carrying that tag.
4. overview gains a final line "tags: a=2 b=1" (entries sorted by tag name) —
   only when at least one tag exists across the tasks.
5. The tag-suffix logic in task-line must live in its own function
   (slopp cohort: use edit_extract).
6. Rename prioritize to by-priority everywhere (slopp cohort: edit_rename).
Update/extend tests; existing tests stay green.
Done: all three namespaces green (+ labeled checkpoint for the slopp cohort).
