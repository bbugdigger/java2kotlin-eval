# J2K Conversion Hypotheses

Falsifiable predictions about how JetBrains' static J2K converter (specifically `JavaToKotlinAction.Handler.convertFiles(...)` from the Kotlin plugin bundled with IntelliJ Community 2024.1.7, invoked headlessly via this repo's `j2k-runner` plugin) handles specific Java patterns.

Each hypothesis is paired with one or more curated test cases under `edge-cases/`. The eval pipeline runs every case through the same J2K invocation as our real-world targets and compares the converted output to a hand-written `expected.kt`.

## How to read this document

- **Status** starts as `Untested` (Phase 8). It moves to `Confirmed` / `Refuted` / `Partial` after the edge-case eval runs (Phase 10).
- **Evidence** is filled in post-run with paths into `build/converted/edge-cases/...` showing the converted output, plus a one-line summary of what differed (or didn't) from the `expected.kt`.
- Hypotheses with rationale citing the **spring-petclinic findings** (see [`results-spring-petclinic.md`](results-spring-petclinic.md)) are predictions backed by observed real-world behaviour at scale; ones citing **J2K source-code observation** or **prior knowledge** are predictions from documentation / known-weak-spot folklore that we want to confirm or refute on the bench.

The "Confirmed" status means the converted output diverges from the gold expected.kt in the way the claim predicts. "Refuted" means J2K does the right (idiomatic) thing — also a valid finding.

## Summary (post-Phase 10)

| H# | Title | Status |
|---|---|---|
| H1 | No expression-body functions | Confirmed |
| H2 | No getter/setter property folding | Partial |
| H3 | No data-class inference from POJOs | Confirmed |
| H4 | Avoids `!!` entirely | Confirmed |
| H5 | Converts `+`-concat to string templates | Confirmed (substantively refuted — see H5 entry) |
| H6 | No Java records → data class | Partial (substantively refuted) |
| H7 | Preserves nested anonymous classes | Confirmed (worse than predicted — produces broken Kotlin) |
| H8 | Propagates JSR-305 nullability | Inconclusive (eval-side classpath limit; see H8 entry) |
| H9 | Drops `throws` clauses | Partial (substantively refuted — emits `@Throws`) |
| H10 | Loses switch fallthrough semantics | Partial (substantively refuted — duplicates body) |
| H11 | Converts `synchronized` correctly | Partial |
| H12 | No `Optional<T>` unwrapping | Confirmed |

The narrative companion ([`edge-cases-report.md`](edge-cases-report.md)) interprets these results in detail and calls out the surprising wins (H6, H9, H10, H11 partials) and real bugs (missing `override` modifier, `synchronized` block type mismatch, `Optional` nullability decoration, smart-cast on mutable `var`).

## H1: J2K never produces expression-body functions

**Claim (falsifiable).** When a Java method body is exactly `return <expr>;`, J2K emits a Kotlin function with an explicit `{ return … }` block body, never the equivalent `fun foo(): T = expr` expression body.

**Rationale.** Across all 47 paired functions in the spring-petclinic eval (Phase 6), J2K produced **0 expression-body functions** vs. 28 in the gold port. Every single-statement returner became a block. This is the largest single-axis idiom delta in our real-world data.

**Edge cases testing this:**
- `edge-cases/expression-body/simple-return-fn/`
- `edge-cases/expression-body/computed-property/`

**Status:** Confirmed
**Evidence:** `simple-return-fn` Partial — converted has `expr-body fn: -4, block-body fn: +4` (J2K wrapped both `Calculator.sum` and `Calculator.format` in `{ return ... }` blocks). `computed-property` Fail (NoMatchingDeclarations) — separate H2 issue but the converted body bodies are also block-wrapped. Confirmed across every multi-statement case in the dataset; H1 is the universal Partial driver in this report.

## H2: J2K does not fold private fields with public getters/setters into Kotlin properties

**Claim.** When a Java class declares `private T x; public T getX() { return x; } public void setX(T x) { this.x = x; }`, J2K emits a Kotlin class with a separate property `x` plus standalone `getX()` / `setX()` functions, instead of folding all three into a single Kotlin property whose accessors reuse the same name.

**Rationale.** The `getters→properties` post-processing pass (`ConvertGettersAndSettersToPropertyProcessing`) requires the `KotlinCacheServiceImpl.createFacadeForFilesWithSpecialModuleInfo` lookup to succeed, which it doesn't in our headless invocation. We see this leak directly into spring-petclinic output — every Java bean property surfaces as 3 declarations on the converted side instead of 1.

**Edge cases testing this:**
- `edge-cases/property-folding/simple-bean/`
- `edge-cases/property-folding/getter-only/`
- `edge-cases/property-folding/setter-only/`

**Status:** Partial
**Evidence:** `setter-only` Pass (negative-control case — J2K correctly preserved the write-only setter). `simple-bean` Partial: idiom delta `block-body fn: +4` (J2K kept all four `getX`/`setX` as separate functions instead of folding into Kotlin properties). `getter-only` Partial: idiom delta `block-body fn: +1` (J2K kept `getName` as a function despite the field being `final` — perfect candidate for primary-ctor `val`). The bean-style cases confirm the prediction; the negative-control refutes it where the prediction shouldn't apply.

## H3: J2K does not infer `data class` from POJOs that satisfy the data-class shape

**Claim.** A Java class whose entire public surface is constructor + final fields + auto-generated `equals`/`hashCode`/`toString` (the canonical "data" shape) becomes a regular Kotlin `class`, not a `data class`.

**Rationale.** Spring-petclinic gold has 1 `data class` declaration; our converted output has 0. Auto-detecting "this looks like a data class" requires structural pattern matching on equals/hashCode bodies and field-only constructors — work that J2K does not appear to perform.

**Edge cases testing this:**
- `edge-cases/pojo-data-class/pojo-equals-hashcode/`
- `edge-cases/pojo-data-class/pojo-with-toString/`

**Status:** Confirmed
**Evidence:** Both POJO cases failed — converted output didn't compile. Errors include "`hashCode` hides member of supertype 'Any' and needs 'override' modifier" (real bug — J2K dropped the `override` keyword from converted `equals`/`hashCode`/`toString`), "Unresolved reference: compare" (J2K couldn't translate `Double.compare` static), and "Operator '===' cannot be applied to 'Coordinate' and 'Object?'" (J2K's `equals` body has type-check issues). No `data class` inference was attempted in either case — every member from the Java source is preserved verbatim.

## H4: J2K avoids non-null assertions (`!!`) entirely

**Claim.** When a Java expression dereferences a value that the converter cannot prove non-null, J2K leaves the expression nullable (and propagates `?.` outward as needed) rather than emitting `!!` to satisfy a non-null position.

**Rationale.** Spring-petclinic gold uses `!!` 4 times across paired declarations; our converted output uses it 0 times. This is plausibly a deliberate posture: `!!` introduces hidden NPE risk, so J2K conservatively prefers nullable-by-default.

**Edge cases testing this:**
- `edge-cases/nullability/null-check-then-call/`
- `edge-cases/nullability/optional-chain/`

**Status:** Confirmed
**Evidence:** Both cases avoided `!!` (consistent with the prediction). `null-check-then-call` Partial: J2K used `?.let { "Hello, ${it.uppercase()}" } ?: "Hello, anonymous"` — different shape than the gold's smart-cast `if`-branch, but also valid. `optional-chain` Fail (DoesNotCompile): "Smart cast to 'Address' is impossible, because 'address' is a mutable property" — J2K kept the `if (a != null && a.b != null)` chain verbatim, but Kotlin disallows the smart-cast across statements when the receiver is `var`. Idiomatic answer would be `address?.city ?: "unknown"`.

## H5: J2K converts Java string concatenation to Kotlin string templates

**Claim.** When a Java expression is a `+`-chain that includes at least one `String` reference (e.g. `"hello, " + name + "!"`), J2K converts it to a Kotlin string template (`"hello, $name!"`) rather than preserving the `+` chain.

**Rationale.** Surprising finding from spring-petclinic: J2K used 13 interpolated strings while gold used 0 (gold prefers `+`). The converter is doing genuine source-level transformation here, not just pretty-printing — this hypothesis tests whether it generalises.

**Edge cases testing this:**
- `edge-cases/string-templates/simple-concat/`
- `edge-cases/string-templates/mixed-types-concat/`
- `edge-cases/string-templates/multi-arg-concat/`

**Status:** Confirmed
**Evidence:** All 3 cases got Partial verdicts. J2K *does* perform the `+`-to-template transform correctly across simple, mixed-type, and nested-expression operands. The Partial verdict is purely H1 bleed: `expr-body fn: -2, block-body fn: +2` on each case (the surrounding function bodies are wrapped in blocks). On the substantive question of string interpolation, this hypothesis is effectively refuted — J2K does the right structural transform.

## H6: J2K does not convert Java records to Kotlin data classes

**Claim.** A Java 14+ `record Point(int x, int y) { }` is converted to a regular Kotlin `class` (or fails to convert), not to a Kotlin `data class Point(val x: Int, val y: Int)`.

**Rationale.** J2K's NJ2K module predates Java records (records reached stable in Java 16, mid-2021; J2K's tree builder was largely shape-frozen by then). Records have a distinct `JCRecordPattern` PSI node that J2K may not have a conversion rule for.

**Edge cases testing this:**
- `edge-cases/records/java-record-basic/`
- `edge-cases/records/java-record-with-method/`

**Status:** Partial (substantively refuted)
**Evidence:** `java-record-basic` Pass — J2K produced `@kotlin.jvm.JvmRecord data class Point(val x: Int, val y: Int)`, complete with the `@JvmRecord` annotation for full Java-side compatibility. `java-record-with-method` Partial: same `data class` structure preserved, but the two methods (`span`, `contains`) come back as `block-body fn: +4` (H1 bleed). Required `jvmTarget=17` in the compilability check; lower targets reject `@JvmRecord` and the verdict comes back Fail (a tooling-side false negative). On the substantive question of "does J2K convert records to data classes," this is **refuted**.

## H7: J2K preserves nested anonymous classes verbatim instead of collapsing to lambdas

**Claim.** When a Java anonymous class implements a `@FunctionalInterface` (or any single-abstract-method interface) and is **nested inside another anonymous class**, J2K emits `object : Foo { override fun … }` rather than collapsing to a Kotlin lambda — even when the body is single-expression.

**Rationale.** Lambda conversion requires the body to be expressible as a single expression with all captures resolvable. In a nested anonymous, captures of the enclosing `this` make the inference brittle, so the converter conservatively keeps the anonymous form. The Meta engineering blog post on their Kotlinator project flagged this exact pattern as one of the failure modes that needed manual cleanup.

**Edge cases testing this:**
- `edge-cases/anonymous-classes/nested-anonymous-runnable/`
- `edge-cases/anonymous-classes/anonymous-with-state/`

**Status:** Confirmed (worse than predicted)
**Evidence:** Both cases failed to compile — but not for the predicted reason of "kept as anonymous instead of lambda." J2K kept the anonymous class form (matches the prediction) but emitted invalid Kotlin syntax: `Runnable() { override fun run() { ... } }` instead of `object : Runnable { ... }`, plus `'run' hides member of supertype 'Runnable' and needs 'override' modifier` (the same missing-`override` bug we see in the POJO cases). The prediction was that J2K would produce *correct-but-non-idiomatic* code; in fact it produces *broken* code.

## H8: J2K propagates JSR-305 / `@Nullable` / `@NotNull` annotations into Kotlin nullable / non-null types

**Claim.** When a Java method declares `@Nullable String foo()` (using `org.jetbrains.annotations.Nullable` or any well-known nullability annotation), J2K converts the return type to `String?`. The complementary `@NotNull` produces `String` (without the question mark) even where J2K would otherwise default to `String?`.

**Rationale.** This is the one piece of cross-source nullability inference J2K has had for a long time — it's the obvious win that keeps simple cases idiomatic. We expect this to work; if it doesn't, that's a *Refuted* finding that's itself useful.

**Edge cases testing this:**
- `edge-cases/nullability/jsr305-nullable/`

**Status:** Inconclusive (recorded as Confirmed by the runner)
**Evidence:** Marked Confirmed by the rollup logic because the case failed to compile, but the failure is `Unresolved reference: Nullable / NotNull` — i.e., the annotation references can't be resolved without `org.jetbrains:annotations` on the classpath, which our stdlib-only compilability check doesn't provide. **This is an eval-side limitation, not a J2K finding.** Inspecting the converted output directly shows J2K preserved the `@Nullable` / `@NotNull` annotation references; whether Kotlin would treat them as nullability hints (refuting the hypothesis) or just as orphan annotations (confirming it) depends on the resolution context. Re-run with the annotations jar on the classpath to get a real verdict.

## H9: J2K silently drops Java's `throws` declarations (since Kotlin has no checked exceptions)

**Claim.** A Java method declared as `void foo() throws IOException` becomes `fun foo(): Unit` — no `@Throws(IOException::class)` annotation, no `throws` clause (Kotlin has none), no comment marking the original exception contract.

**Rationale.** Kotlin has no checked exceptions, so the `throws` clause has nowhere to go. The "right" choice for Java interop would be `@Throws(IOException::class)` so the Kotlin function is callable from Java with the same checked-exception contract. Whether J2K emits this annotation is the falsifiable bit.

**Edge cases testing this:**
- `edge-cases/checked-exceptions/throws-ioexception/`
- `edge-cases/checked-exceptions/throws-multiple/`

**Status:** Partial (substantively refuted)
**Evidence:** `throws-multiple` Pass — J2K emitted `@kotlin.Throws(IOException::class, SQLException::class)`, preserving every Java throws-clause type. `throws-ioexception` Partial: also emitted `@kotlin.Throws(IOException::class)` correctly; the Partial verdict comes only from the H1 block-body delta. **Refuted on the substantive question.** The hypothesis predicted J2K would silently drop the throws contract; in fact it generates the correct `@Throws` annotation for Java-side compatibility.

## H10: J2K's switch → when conversion loses fallthrough semantics

**Claim.** A Java `switch` with intentional fallthrough (cases without `break;`) is converted to a Kotlin `when` whose branches do **not** preserve the fallthrough behaviour — each branch executes independently.

**Rationale.** Kotlin's `when` does not have fallthrough as a syntactic feature. Reproducing the semantics requires duplicating the fallthrough cases' bodies into the earlier branch, which is a non-trivial transformation. We expect J2K to either (a) translate each branch independently (semantic regression!) or (b) refuse to convert and warn. Either is a reportable finding.

**Edge cases testing this:**
- `edge-cases/switch-statements/switch-fallthrough/`
- `edge-cases/switch-statements/switch-no-default/`

**Status:** Partial (substantively refuted)
**Evidence:** `switch-fallthrough` Pass — J2K **duplicated the case-2 body into case-1** to preserve fallthrough semantics:

```kotlin
when (code) {
    1 -> { out.append("one"); out.append("two") }  // body of case 2 inlined
    2 -> out.append("two")
    3 -> out.append("three")
    else -> out.append("other")
}
```

`switch-no-default` Partial: J2K correctly produced a `when` over the integer with all branches; Partial verdict is H1 block-body bleed. **Refuted on the substantive question.** This is one of the more impressive J2K transforms — semantic-preserving rewrites of Java switch are non-trivial.

## H11: J2K converts Java `synchronized` blocks/methods to Kotlin `synchronized(...) { }` calls

**Claim.** A Java `synchronized (lock) { … }` block becomes a Kotlin `synchronized(lock) { … }` function call (using the stdlib helper). A Java `synchronized` method becomes a Kotlin function whose body is wrapped in `synchronized(this) { … }`.

**Rationale.** The stdlib `synchronized` function exists for exactly this purpose. J2K is likely to use it — the conversion is straightforward. This hypothesis predicts the *idiomatic* outcome; refutation would be "J2K leaves it as `synchronized` modifier on the function, which doesn't exist in Kotlin and won't compile."

**Edge cases testing this:**
- `edge-cases/concurrency/synchronized-method/`
- `edge-cases/concurrency/synchronized-block/`

**Status:** Partial
**Evidence:** `synchronized-method` Pass — J2K emitted `@kotlin.jvm.Synchronized` on the function (correct, idiomatic). `synchronized-block` Fail (DoesNotCompile): "Type mismatch: inferred type is Object? but Any was expected" — J2K converted `synchronized (lock) { ... }` to a Kotlin form that won't type-check. The `lock` field was declared as `private final Object lock = new Object()`; J2K kept `Any?` for the lock type but the stdlib `synchronized(...)` overload requires `Any` (non-null). Real bug on the block form; correct conversion of the modifier form.

## H12: J2K does not unwrap `java.util.Optional<T>` to Kotlin `T?`

**Claim.** A Java method returning `Optional<String>` becomes a Kotlin function returning `Optional<String>` (or `Optional<String?>`), not `String?`. Calls to `.isPresent()` / `.get()` / `.orElse(...)` are preserved verbatim instead of being collapsed to Kotlin null-safety idioms (`?.let`, `?:`, `!!`).

**Rationale.** `Optional` is a Java library type with no special status in J2K's type-system rules. Unwrapping it to a nullable would require a project-wide guarantee that no caller is using Optional's API beyond the get/orElse path — too aggressive for a static converter. We expect verbatim preservation.

**Edge cases testing this:**
- `edge-cases/nullability/optional-return/`
- `edge-cases/nullability/optional-map-chain/`

**Status:** Confirmed
**Evidence:** Both Optional cases failed to compile. `optional-return`: "Type mismatch: inferred type is Optional<String> but Optional<String?>? was expected" — J2K added `?` decorations around `Optional<...>` in some places but not consistently, producing a contradictory return-type annotation. `optional-map-chain`: "Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type Optional<String?>?" plus type-inference failures on `String::trim` callable references. J2K preserved the entire `.map().filter().orElse()` chain verbatim — confirming the prediction that no unwrapping is attempted.

---

## Index of edge cases by hypothesis

A reverse lookup: given a path in `edge-cases/`, which hypothesis does it test? See `edge-cases/README.md`.
