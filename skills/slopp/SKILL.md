---
name: slopp
description: "Work efficiently with a slopp codebase over MCP: form-addressed reads/writes with built-in verification, provenance, and a live REPL oracle. Read this before your first slopp tool call."
---

# Working with slopp

slopp is an agent-native codebase: code lives in a **store** (not files), the
unit of everything is the **top-level form**, and a **live JVM image** runs
your code continuously. Every write is verified immediately and recorded with
your stated intent. There are no files, paths, or line numbers — you address
code as `namespace` + `form name`.

Server: `clojure -M -m slopp.mcp` (stdio) from the slopp repo.

## The workflow loop

1. **Orient with ONE call.** `query_project` → every namespace with its full
   outline (names, arities, doc lines, `!`-effect status, test-ness). Then
   `query_search {pattern}` to FIND things (the grep — search before reading),
   and read actual code one form at a time (`query_symbol`); `query_source`
   for a whole namespace only when you truly need it.
2. **Write with intent.** Every write takes a `prompt` — one line of *why*.
   It becomes permanent provenance (`query_lineage` shows a form's life as
   add → replace → rename with your reasons). Don't skip it.
3. **Trust the verification you get back.** Every write hot-reloads and
   re-runs exactly the tests that exercise the touched form(s). You do NOT
   need to call `test_run` after edits — the result is already in the
   response.
4. **Your EPISODE is tracked for you.** Everything you do between your
   checkpoints is one automatic work unit: `query_changes {agent}` shows the
   net per-form diff, steps, and red→green arc since your last stable spot;
   `episode_revert {agent}` scraps the whole attempt back to it (forms other
   agents touched are skipped, never stomped). Pass a DISTINCT `agent` label
   on every call — essential when sub-agents work in parallel, or your
   episodes braid together. `query_history {collapse: true}` reads the
   long-term history at episode grain.
5. **Checkpoint at unit boundaries.** When a piece of work is done, call
   `checkpoint {label}` — it tidies the forms you touched (deterministic,
   behavior-preserving rewrites, re-verified) and marks the boundary in
   history.

## Choosing the right write tool

| Situation | Tool |
|---|---|
| New namespace, content known up front | `ingest` — the whole namespace's source in ONE verified call (new namespaces only; never overwrites) |
| New namespace, building incrementally | `ns_create` (create dependencies FIRST — a require of a not-yet-created ns fails) |
| New/removed require | `ns_add_require` / `ns_remove_require` (never hand-edit the ns form) |
| New function/test | `edit_add_form` (one form per call) |
| Change a function | `edit_replace_form` (submit the whole new form) |
| Small change INSIDE a big form | `edit_subform {ns form match source}` — give the exact subexpression and its replacement; never re-transcribe the rest (wrap = a replacement containing the match) |
| Undo a change | `edit_revert {ns name}` (previous version) or `{:to delta-id}` from `query_form_history` |
| Change SEVERAL forms for one reason | `edit_group {steps: [{action: "replace"\|"add"\|"delete", ns, name, source}], prompt}` — atomic, verified once; sequencing single edits burns a false red between them |
| Rename anything | `edit_rename` — rewrites the def + every reference across namespaces, shadow-safe; NEVER rename by editing call sites yourself |
| Reorder forms | `edit_move` (form X to just before form Y) |
| Extract a helper | `edit_extract` — args `{ns, from, form, name}` where `form` is the exact subform source; params (the free locals) are computed for you, placement and the call-site rewrite are handled, behavior is re-verified |
| Delete | `edit_delete_form` |
| Try something risky / parallel workstream | `branch_create {name}` → work normally (verified writes) → `branch_switch {name: "main"}` → `branch_merge {name}`. `query_branches` shows where you are; switching resets test-trace narrowing until the next run |
| Merge a diverged copy of the project | `merge_from {dir}` — different-form work lands; same-form divergence returns `:conflicts` (ours kept, theirs surfaced; resolve with `edit_replace_form`) |

**Batch related changes.** Verification runs per WRITE — so a feature that
touches several forms should be ONE `edit_group` (even mixing adds and
replaces), not a stream of single writes. Fewer, larger intents are both
faster and cleaner history. And you almost never need `test_run` after edits:
every write's response already contains the verification result.

**Every write must compile.** A form referencing something undefined is
rejected on the spot (`{:error "...failed to compile: Unable to resolve..."}`)
— nothing commits. So **define callees before callers**; for mutual recursion
add `(declare name)` first, exactly as in ordinary Clojure.

**Red-first TDD, slopp-style:** add the function with a deliberately minimal
body AND its test in ONE `edit_group` — that group's verification returns the
honest red with `:failures` inline; then `edit_replace_form` the real
implementation for green. Two writes total. Don't stub-dance across many
single writes; the per-write verification makes every red free to observe.

## Reading results

- Green + quiet ⇒ terse `{:ok true :delta "d42" :tests {:ran 2 :pass 5} :affected 2}`.
  Pass `:verbose true` if you want the full map.
- **Red ⇒ `:test :failures`** carries expected/actual/exception per failure —
  diagnose from the response; you rarely need another round trip. Stack
  traces cite `file.clj:line` in exactly the coordinates `query_source` shows.
- `:fresh-confirmed true` — the red survived a fresh image: it's real.
- `:staleness-detected true` — the red was image staleness, already healed;
  the reported result is trustworthy.
- `:warnings` — `!`-naming violations YOU just introduced (functions that
  modify state must end in `!`); fix with `edit_rename` using the `:suggest`.
  `:existing-warnings n` counts older ones you didn't cause.
- `:untested true` — no test exercises the form you changed. Consider adding
  one.
- `:affected` — which tests re-ran (`:all` = no trace info yet; run
  `test_run` once to build the map and narrowing kicks in).

## The oracle: answering questions by running code

`query_eval` is your REPL: call any function — including effectful ones — to
observe real behavior instead of reading callers. It cannot define or modify
*code* (use edit tools); runtime state it perturbs is disposable (`restart`
rebuilds a faithful image from the store).

- "What does this return for X?" → `query_eval "(my.ns/f X)"`
- "What flows through f when the system runs?" → `query_observe {ns name code}`
  — captures each call's args/return while your driver code runs. Use this
  instead of reading callers to figure out shapes.
- "What does this macro do?" → `query_macroexpand {code}`
- "Who calls this?" → `query_references`
- "Why does this test fail?" → the `:failures` in the result, then
  `query_eval` to probe.
- Feeling that the image is lying (weird arity errors, unbound vars) →
  `restart` and re-run; it's cheap.

## Habits that pay

- Outline before source; source one form at a time. Reading whole namespaces
  is the expensive path.
- One logical change per write, with a real prompt — the history is only as
  good as your intents.
- Multi-form intent = `edit_group`, always.
- Add the test in the same breath as the function; narrowing and `:untested`
  only work when tests exist.
- `test_run {:only [name]}` re-runs a single test while iterating on it.
- Full-project sweep = `test_run` with NO `ns` — every namespace's tests in
  one call. Never loop test_run over namespaces.
- Checkpoint when you'd naturally say "done with that".

## Shipping

`build {dir}` materializes a plain files project (absolute path, outside any
repo you're working in). Add `main` (a qualified entry fn like
`"calc.core/run-cli"`) and the output also carries a native-binary recipe:
running the emitted `./build-native.sh` (needs GraalVM 21+ on PATH) compiles
a self-contained executable — instant startup, no JVM required to run. Your
entry fn either takes the CLI args as one vector (single arity-1 fn) or as
varargs; the generated launcher adapts to whichever you wrote.
