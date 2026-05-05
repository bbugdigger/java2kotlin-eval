# throws-ioexception

**Tests:** [H9](../../../docs/hypotheses.md#h9-j2k-silently-drops-javas-throws-declarations-since-kotlin-has-no-checked-exceptions).

A method declares `throws IOException` — the canonical checked-exception use case. Idiomatic interop-friendly Kotlin uses `@Throws(IOException::class)` so Java callers still see the same checked-exception contract.

H9 says J2K drops the throws clause without emitting `@Throws`. If that happens, Java code calling this Kotlin function won't be required to handle `IOException` — silent contract change.
