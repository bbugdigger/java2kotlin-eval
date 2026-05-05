# throws-multiple

**Tests:** [H9](../../../docs/hypotheses.md#h9-j2k-silently-drops-javas-throws-declarations-since-kotlin-has-no-checked-exceptions).

Multiple throws clauses, each actually exercised by the body. The `@Throws(...)` annotation accepts multiple class arguments — the idiomatic conversion preserves all of them.

This is a stricter version of `throws-ioexception`: even if J2K handles the single-exception case, multi-exception throws is more likely to slip through (the converter would have to extract every Throwable from the throws clause and inject all of them into the annotation).
