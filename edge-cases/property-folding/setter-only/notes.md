# setter-only

**Tests:** [H2](../../../docs/hypotheses.md#h2-j2k-does-not-fold-private-fields-with-public-getterssetters-into-kotlin-properties).

Write-only field with a setter and no getter. Kotlin properties don't natively express "writable from outside but unreadable" — the closest idiom is a private property with a public setter function (kept as the original `setSecret` for binary compatibility), which is what `expected.kt` shows.

This case is interesting because **even an idiomatic Kotlin port of this Java would keep the setter function** — H2's prediction (no folding) might be the *correct* behaviour here. If H2 holds and J2K just keeps the function, that's actually fine; if J2K tries to fold it into a property and discards the write-only semantic, that's a behavioural regression worth surfacing.
