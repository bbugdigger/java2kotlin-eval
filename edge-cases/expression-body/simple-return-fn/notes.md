# simple-return-fn

**Tests:** [H1](../../../docs/hypotheses.md#h1-j2k-never-produces-expression-body-functions) — J2K never produces expression-body functions.

The two methods on `Calculator` each have a body of exactly `return <expr>;` — the canonical shape that should fold into Kotlin's `fun … = expr` expression-body form.

If H1 holds, J2K will emit `fun sum(a: Int, b: Int): Int { return a + b }` instead of the `= a + b` form in `expected.kt`.
