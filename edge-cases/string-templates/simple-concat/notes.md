# simple-concat

**Tests:** [H5](../../../docs/hypotheses.md#h5-j2k-converts-java-string-concatenation-to-kotlin-string-templates).

The simplest possible concatenation: literal + identifier + literal. Idiomatic Kotlin uses string interpolation: `"Hello, $name!"`.

H5 says J2K performs this transformation. Spring-petclinic data already supports this — converted output had 13 interpolated strings vs. 0 in gold. This case confirms the simplest form works.
