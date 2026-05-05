# getter-only

**Tests:** [H2](../../../docs/hypotheses.md#h2-j2k-does-not-fold-private-fields-with-public-getterssetters-into-kotlin-properties).

Read-only field with a single getter — the field is `final` and only set in the constructor. Idiomatic Kotlin folds this into a primary-constructor `val` property.

This is a strict subset of the simple-bean case (no setter to handle) and tests whether J2K's getters→properties pass — when it works at all — handles the immutable case correctly. If even the getter-only case isn't folded, the post-processor pass is fully bypassed.
