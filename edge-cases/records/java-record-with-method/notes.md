# java-record-with-method

**Tests:** [H6](../../../docs/hypotheses.md#h6-j2k-does-not-convert-java-records-to-kotlin-data-classes).

A Java record with two custom methods. Idiomatic Kotlin: `data class` with the methods preserved as expression-body functions, and the `n >= low && n <= high` predicate optionally collapsed to the cleaner `n in low..high`.

If H6 holds, the expected output is the larger gap — not just the data-class wrapping is missed but also the methods come back as block-body fns (per H1).
