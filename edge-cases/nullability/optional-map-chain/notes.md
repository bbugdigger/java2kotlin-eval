# optional-map-chain

**Tests:** [H12](../../../docs/hypotheses.md#h12-j2k-does-not-unwrap-javautiloptional-to-kotlin-t).

A whole pipeline of `.map().filter().orElse()` over an `Optional<String>`. Idiomatic Kotlin replaces this with `?.let { ... } ?:` or `?.takeIf { } ?: default` over a nullable `String?`.

This is a strict superset of the `optional-return` case: the converter would need to recognize not just the Optional return shape but also the `.map()`, `.filter()`, and `.orElse()` calls as candidates for nullable-chain rewriting. Even if H12 is partially refuted on the simple return case, this case probably still confirms it.
