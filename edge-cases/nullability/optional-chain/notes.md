# optional-chain

**Tests:** [H4](../../../docs/hypotheses.md#h4-j2k-avoids-non-null-assertions--entirely).

The Java pattern `if (a != null && a.b != null) a.b` is the textbook case for Kotlin's safe-call + Elvis chain: `a?.b ?: default`. Both operands of the boolean-and need to be transformed into one connected expression, which is a non-trivial structural rewrite.

H4 expects J2K avoids `!!`, which is consistent with using `?.` here. The interesting question is whether J2K **actually rewrites the structure** to use `?.` (idiomatic) or **preserves the if-chain verbatim** with separate null checks (technically correct, less idiomatic).
