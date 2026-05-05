# synchronized-method

**Tests:** [H11](../../../docs/hypotheses.md#h11-j2k-converts-java-synchronized-blocksmethods-to-kotlin-synchronized--calls).

Java's method-level `synchronized` modifier on an instance method is equivalent to wrapping the body in `synchronized (this) { ... }`. Idiomatic Kotlin uses the `@Synchronized` annotation from `kotlin.jvm`, which produces the same bytecode (an `ACC_SYNCHRONIZED` flag on the method).

H11 expects J2K to do the right thing here — `@Synchronized` is the standard Kotlin idiom and there's no plausible reason a converter would miss it.
