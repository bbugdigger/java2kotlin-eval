# anonymous-with-state

**Tests:** [H7](../../../docs/hypotheses.md#h7-j2k-preserves-nested-anonymous-classes-verbatim-instead-of-collapsing-to-lambdas).

The anonymous class has a **mutable field** (`count`). A Kotlin lambda cannot hold mutable state directly — you'd need to capture an external `var` (which crosses lambda boundaries differently than fields), or wrap state in an `AtomicInteger`-equivalent. So **even gold should not collapse this to a lambda** — `expected.kt` keeps the anonymous form via `object : IntSupplier`.

This is the negative-control case for H7: J2K *should* preserve the anonymous form here. If it tries to collapse and ends up with broken state semantics, that's a bug worth flagging. If it correctly keeps the `object : IntSupplier` form, that's a refutation-by-correctness — H7 still fires on the simpler nested case but not here.
