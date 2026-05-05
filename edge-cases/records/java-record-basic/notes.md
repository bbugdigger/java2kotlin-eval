# java-record-basic

**Tests:** [H6](../../../docs/hypotheses.md#h6-j2k-does-not-convert-java-records-to-kotlin-data-classes).

The simplest possible Java record. Idiomatic Kotlin equivalent is a one-line `data class` with `val` parameters.

H6 expects J2K to either fail to convert (records' PSI nodes may not be in J2K's transform table) or convert to a regular class with explicit accessors mimicking the record's auto-synthesized ones.
