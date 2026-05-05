# switch-fallthrough

**Tests:** [H10](../../../docs/hypotheses.md#h10-j2ks-switch--when-conversion-loses-fallthrough-semantics).

Java switch with intentional fallthrough: case 1 has no `break`, so it falls into case 2 and runs both bodies (appending "one" then "two"). Cases 2 and 3 have `break` — independent.

Kotlin's `when` has no fallthrough syntax. The semantically-correct conversion duplicates the case-2 body into case 1 (what `expected.kt` shows). H10 predicts J2K does **not** do this duplication — it produces independent branches, silently dropping the fallthrough behaviour for code calling `label(1)`. That'd be a behavioural regression: callers that expect "onetwo" would get just "one".
