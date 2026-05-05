# synchronized-block

**Tests:** [H11](../../../docs/hypotheses.md#h11-j2k-converts-java-synchronized-blocksmethods-to-kotlin-synchronized--calls).

Java's `synchronized (lock) { ... }` block has a direct Kotlin equivalent in the stdlib `synchronized(lock) { ... }` inline function. The block returns a value (the lazily-computed `value`), so the Kotlin form folds naturally into the function's expression body.

Note that `expected.kt` uses `value!!` because the smart-cast inside `synchronized` has been the subject of compiler issues; the `!!` is the safest way to express "we just set it if needed, it's now non-null." A more cautious writer might use `?: error("...")` or pull the result into a local. Either way the lock semantics are the same.
