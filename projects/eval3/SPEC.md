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
