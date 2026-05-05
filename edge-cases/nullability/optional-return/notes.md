# optional-return

**Tests:** [H12](../../../docs/hypotheses.md#h12-j2k-does-not-unwrap-javautiloptional-to-kotlin-t).

`Optional<T>` as a return-type sentinel for "absence" is idiomatic Java but anti-idiomatic Kotlin — Kotlin uses `T?` directly. The idiomatic Kotlin port unwraps `Optional.empty()` → `null` and `Optional.of(x)` → `x`, returning `String?` instead of `Optional<String>`.

H12 predicts J2K leaves the Optional API verbatim, since unwrapping requires a project-wide guarantee no caller uses Optional's other methods. Refutation here would be a notable feature.
