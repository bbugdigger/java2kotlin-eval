# multi-arg-concat

**Tests:** [H5](../../../docs/hypotheses.md#h5-j2k-converts-java-string-concatenation-to-kotlin-string-templates).

Concatenation that includes a parenthesized **conditional expression** (`active ? "..." : "..."`). The idiomatic Kotlin nests the if-expression inside `${...}` braces inside the template — a non-trivial PSI-level rewrite.

This is the stress-test for H5: does the interpolation transform handle nested expressions, or does it bail when the operand isn't a simple identifier and fall back to `+` for that piece?
