# pojo-with-toString

**Tests:** [H3](../../../docs/hypotheses.md#h3-j2k-does-not-infer-data-class-from-pojos-that-satisfy-the-data-class-shape).

Same shape as `pojo-equals-hashcode` plus a custom `toString` that doesn't match Kotlin's auto-generated form. Idiomatic Kotlin: `data class` with the custom `toString` retained as an explicit `override`.

This case adds a wrinkle to H3: even if J2K could detect the equals+hashCode pair as a data-class candidate, the custom `toString` complicates the decision (data class auto-generates toString, but here we want a custom one). A fully smart converter would still emit `data class Pair(...) { override fun toString() = ... }`. We expect H3 to hold here too — if anything more strongly, since the custom toString gives J2K an excuse to bail on data-class inference.
