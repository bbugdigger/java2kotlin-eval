# mixed-types-concat

**Tests:** [H5](../../../docs/hypotheses.md#h5-j2k-converts-java-string-concatenation-to-kotlin-string-templates).

Concatenation involving non-String values (Int, Double). In Java these implicit conversions happen via `String.valueOf(...)`; in Kotlin string templates handle them natively via `toString()`.

Tests whether J2K's interpolation transform is type-aware enough to handle non-String operands without breaking, or whether it falls back to verbatim `+` when types diverge.
