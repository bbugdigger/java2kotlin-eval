# switch-no-default

**Tests:** [H10](../../../docs/hypotheses.md#h10-j2ks-switch--when-conversion-loses-fallthrough-semantics) (control case for the no-fallthrough scenario).

Switch without `default`, where every branch returns. Idiomatic Kotlin folds the trailing `return "unknown"` into a `when` `else` branch and uses the entire `when` as an expression body.

This is the **negative control** for H10: there's no fallthrough to lose, so a competent converter should produce exactly the `when`-as-expression form. Whether J2K matches the expression-body shape is also tied to H1 (no expr-body fns).
