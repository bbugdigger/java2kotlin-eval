# null-check-then-call

**Tests:** [H4](../../../docs/hypotheses.md#h4-j2k-avoids-non-null-assertions--entirely).

The `if (name != null) name.toUpperCase()` pattern is one Kotlin can smart-cast: inside the if-branch, `name` is non-null, so `name.toUpperCase()` is type-safe without any `?` or `!!`.

This case tests whether J2K (a) emits a smart-cast-equivalent (the idiomatic outcome — what `expected.kt` shows), (b) introduces a defensive `!!`, or (c) keeps the explicit if-branch but uses `?.` even when smart-cast would be cleaner.

H4 predicts (c)-ish — J2K avoids `!!`. The interesting question is whether it goes idiomatic ((a)) or stays defensive.
