# pojo-equals-hashcode

**Tests:** [H3](../../../docs/hypotheses.md#h3-j2k-does-not-infer-data-class-from-pojos-that-satisfy-the-data-class-shape).

`Coordinate` is the canonical "value" shape: two `final` fields, a constructor that initializes both, getters, plus correctly-implemented `equals`/`hashCode` over both fields. Idiomatic Kotlin reduces this to a one-line `data class`.

If H3 holds, J2K emits a regular `class` with all four members preserved verbatim, missing the `data` keyword that would auto-synthesize equals/hashCode/copy/componentN.

This is one of the hypothesis pairs whose refutation would be most useful — if J2K *does* recognize this shape, that's a feature worth knowing about.
