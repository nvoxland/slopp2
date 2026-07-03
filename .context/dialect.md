# Dialect & effects

## The gate (`slopp.edit`)

Every write passes `parse-form`: exactly ONE top-level form, then the
dialect check —
- **D4:** `defmacro` rejected ("user macros are banned").
- **D3 denylist** (analysis defeaters): `eval`, `alter-var-root`, `binding`,
  `gen-class`, `definline`, `read-string`. Extensible — the list is a sample,
  grow it deliberately (and record here).

Philosophy: keep **data dynamism** (open maps, loose args — the advantage the
live oracle makes safe); constrain **metaprogramming dynamism** (what defeats
machine understanding). The denylist is about the *semantic layer*, not the
CST (which can represent anything).

Note: the D3 bans apply to *authored* store code. The host and `slopp.rt`
legitimately use `alter-var-root`/`binding` as instrumentation machinery.

## `!`-effect checking (D6, `slopp.index`)

- A var is **effectful iff it transitively reaches an effectful leaf** through
  the clj-kondo call graph (monotonic fixpoint, cycle-safe).
- Leaf set (`effectful-leaves`): in-process mutation prims (`swap!`, `reset!`,
  `alter`, transients, agents/promises, ...) + external writes (`spit`,
  `delete-file`). Reads and non-determinism (`slurp`, `rand`, `now`) are NOT
  effects — `!` tracks *modification* (what causes reload staleness), not
  referential transparency.
- The rule: name ends in `!` ⇔ computed-effectful. Violations are surfaced as
  warnings on every write (`edit/ns-warnings`) with the exact suggested name;
  fixing one is just `rename!`.
- **Known leak:** higher-order fns are effect-polymorphic and can't be soundly
  marked statically — runtime observation covers them.
- **Open (F7, needs user):** stdout (`println`) is currently NOT a leaf —
  matches idiomatic Clojure (print fns aren't bang-named) but sits oddly with
  "external writes." Recommendation on file: keep `!` = mutation per
  convention; if console IO matters, surface it as separate `:effects` info
  rather than a naming rule.

## Enforcement stance

Honest **labeling**, not capability restriction: the agent may write any
effectful code; the name must tell the truth. Nothing here rejects effects —
only lies about them (and even violations are warnings + auto-fixable, not
hard rejections).
