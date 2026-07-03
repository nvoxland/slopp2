# Verification

The oracle must never return a false verdict. Everything here serves that.

## The pieces

1. **Traced runs (`slopp.rt/traced-run`, injected into every image).**
   Temporarily wraps fn vars of the store namespaces (alter-var-root,
   restored in `finally`), runs each test var individually, returns
   `{:summary {... :failures [...]} :trace {test-sym #{form-sym}}}`.
   - Failure details (F1) are captured by rebinding clojure.test's dynamic
     `report` multimethod — **`:test` counting stays in `test-var` itself;
     the custom report must inc `:pass/:fail/:error` counters**. Bounded:
     ≤20 failures, 400-char values, Throwables as class+message.
   - clojure.test's `:actual` for an `is` failure is the failed predicate
     form, e.g. `(not (= 5 -1))` — keep it; it's more informative than the
     bare value.
   - **Sampling limits (accepted):** value-captured references
     (`(def g (comp f inc))`) bypass var wrapping; multimethods and macros
     are not instrumented; unexercised paths are unobserved.
2. **The trace map (session `:test-map`).** Flat
   `{qualified-test-sym #{qualified-form-sym}}`, merged in on every traced
   run, carried across renames (`rename-in-trace`). Powers **affected-test
   selection**: edit form F → run only tests whose set contains F (or F
   itself if F is a test). No trace info → conservative full-ns run
   (`:affected :all`).
3. **Restart-as-diagnostic (`diagnosed-run!`).** Red on the current image →
   swap to a fresh image (reloaded from the store, faithful by construction)
   and re-run before believing it. red→green ⇒ `:staleness-detected true`
   (healed, no false verdict); red→red ⇒ `:fresh-confirmed true`.
4. **Warm spare.** `{:warm-spare? true}` keeps a `future`-started image
   warming; `fresh-image!` swaps to it (<~3s vs ~6-8s cold boot) and starts
   the next spare. On for the MCP server. `close!` derefs and stops the
   spare — never leak child JVMs.
5. **Provenance.** Every verification lands as a `:verify` delta with the
   summary (incl. `:failures`, `:staleness-detected`/`:fresh-confirmed`).

## Gotchas

- `clojure.test/*test-out*` does NOT follow `with-out-str` — this is exactly
  why rt captures report events instead of parsing output.
- Image stack traces currently say `NO_SOURCE_FILE:<line>` for form-eval'd
  code (F6 open): fix direction is loading rendered source via
  `Compiler/load` with a virtual path so traces map to VFS lines.
- Multi-form refactors must go through `edit-group!` (F2): single-form edits
  verify immediately, so sequencing them hits a meaningless mid-refactor red +
  a diagnostic restart between edits (measured: −49% calculator wall time when
  the two-form fix moved to a group).
- Don't run expensive assertions about timing in tests except with generous
  bounds (warm-spare test asserts <3000ms swap vs ~6-8s boot).
