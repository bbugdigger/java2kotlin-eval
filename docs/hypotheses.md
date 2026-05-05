# J2K Conversion Hypotheses

Falsifiable predictions about how JetBrains' static J2K converter (specifically `JavaToKotlinAction.Handler.convertFiles(...)` from the Kotlin plugin bundled with IntelliJ Community 2024.1.7, invoked headlessly via this repo's `j2k-runner` plugin) handles specific Java patterns.

Each hypothesis is paired with one or more curated test cases under `edge-cases/`. The eval pipeline runs every case through the same J2K invocation as our real-world targets and compares the converted output to a hand-written `expected.kt`.

## How to read this document

- **Status** starts as `Untested` (Phase 8). It moves to `Confirmed` / `Refuted` / `Partial` after the edge-case eval runs (Phase 10).
- **Evidence** is filled in post-run with paths into `build/converted/edge-cases/...` showing the converted output, plus a one-line summary of what differed (or didn't) from the `expected.kt`.
- Hypotheses with rationale citing the **spring-petclinic findings** (see [`results-spring-petclinic.md`](results-spring-petclinic.md)) are predictions backed by observed real-world behaviour at scale; ones citing **J2K source-code observation** or **prior knowledge** are predictions from documentation / known-weak-spot folklore that we want to confirm or refute on the bench.

The "Confirmed" status means the converted output diverges from the gold expected.kt in the way the claim predicts. "Refuted" means J2K does the right (idiomatic) thing — also a valid finding.

## H1: J2K never produces expression-body functions

**Claim (falsifiable).** When a Java method body is exactly `return <expr>;`, J2K emits a Kotlin function with an explicit `{ return … }` block body, never the equivalent `fun foo(): T = expr` expression body.

**Rationale.** Across all 47 paired functions in the spring-petclinic eval (Phase 6), J2K produced **0 expression-body functions** vs. 28 in the gold port. Every single-statement returner became a block. This is the largest single-axis idiom delta in our real-world data.

**Edge cases testing this:**
- `edge-cases/expression-body/simple-return-fn/`
- `edge-cases/expression-body/computed-property/`

**Status:** Untested
**Evidence:** _(filled in Phase 10)_

## H2: J2K does not fold private fields with public getters/setters into Kotlin properties

**Claim.** When a Java class declares `private T x; public T getX() { return x; } public void setX(T x) { this.x = x; }`, J2K emits a Kotlin class with a separate property `x` plus standalone `getX()` / `setX()` functions, instead of folding all three into a single Kotlin property whose accessors reuse the same name.

**Rationale.** The `getters→properties` post-processing pass (`ConvertGettersAndSettersToPropertyProcessing`) requires the `KotlinCacheServiceImpl.createFacadeForFilesWithSpecialModuleInfo` lookup to succeed, which it doesn't in our headless invocation. We see this leak directly into spring-petclinic output — every Java bean property surfaces as 3 declarations on the converted side instead of 1.

**Edge cases testing this:**
- `edge-cases/property-folding/simple-bean/`
- `edge-cases/property-folding/getter-only/`
- `edge-cases/property-folding/setter-only/`

**Status:** Untested
**Evidence:** _(filled in Phase 10)_

## H3: J2K does not infer `data class` from POJOs that satisfy the data-class shape

**Claim.** A Java class whose entire public surface is constructor + final fields + auto-generated `equals`/`hashCode`/`toString` (the canonical "data" shape) becomes a regular Kotlin `class`, not a `data class`.

**Rationale.** Spring-petclinic gold has 1 `data class` declaration; our converted output has 0. Auto-detecting "this looks like a data class" requires structural pattern matching on equals/hashCode bodies and field-only constructors — work that J2K does not appear to perform.

**Edge cases testing this:**
- `edge-cases/pojo-data-class/pojo-equals-hashcode/`
- `edge-cases/pojo-data-class/pojo-with-toString/`

**Status:** Untested
**Evidence:** _(filled in Phase 10)_

## H4: J2K avoids non-null assertions (`!!`) entirely

**Claim.** When a Java expression dereferences a value that the converter cannot prove non-null, J2K leaves the expression nullable (and propagates `?.` outward as needed) rather than emitting `!!` to satisfy a non-null position.

**Rationale.** Spring-petclinic gold uses `!!` 4 times across paired declarations; our converted output uses it 0 times. This is plausibly a deliberate posture: `!!` introduces hidden NPE risk, so J2K conservatively prefers nullable-by-default.

**Edge cases testing this:**
- `edge-cases/nullability/null-check-then-call/`
- `edge-cases/nullability/optional-chain/`

**Status:** Untested
**Evidence:** _(filled in Phase 10)_

## H5: J2K converts Java string concatenation to Kotlin string templates

**Claim.** When a Java expression is a `+`-chain that includes at least one `String` reference (e.g. `"hello, " + name + "!"`), J2K converts it to a Kotlin string template (`"hello, $name!"`) rather than preserving the `+` chain.

**Rationale.** Surprising finding from spring-petclinic: J2K used 13 interpolated strings while gold used 0 (gold prefers `+`). The converter is doing genuine source-level transformation here, not just pretty-printing — this hypothesis tests whether it generalises.

**Edge cases testing this:**
- `edge-cases/string-templates/simple-concat/`
- `edge-cases/string-templates/mixed-types-concat/`
- `edge-cases/string-templates/multi-arg-concat/`

**Status:** Untested
**Evidence:** _(filled in Phase 10)_

## H6: J2K does not convert Java records to Kotlin data classes

**Claim.** A Java 14+ `record Point(int x, int y) { }` is converted to a regular Kotlin `class` (or fails to convert), not to a Kotlin `data class Point(val x: Int, val y: Int)`.

**Rationale.** J2K's NJ2K module predates Java records (records reached stable in Java 16, mid-2021; J2K's tree builder was largely shape-frozen by then). Records have a distinct `JCRecordPattern` PSI node that J2K may not have a conversion rule for.

**Edge cases testing this:**
- `edge-cases/records/java-record-basic/`
- `edge-cases/records/java-record-with-method/`

**Status:** Untested
**Evidence:** _(filled in Phase 10)_

## H7: J2K preserves nested anonymous classes verbatim instead of collapsing to lambdas

**Claim.** When a Java anonymous class implements a `@FunctionalInterface` (or any single-abstract-method interface) and is **nested inside another anonymous class**, J2K emits `object : Foo { override fun … }` rather than collapsing to a Kotlin lambda — even when the body is single-expression.

**Rationale.** Lambda conversion requires the body to be expressible as a single expression with all captures resolvable. In a nested anonymous, captures of the enclosing `this` make the inference brittle, so the converter conservatively keeps the anonymous form. The Meta engineering blog post on their Kotlinator project flagged this exact pattern as one of the failure modes that needed manual cleanup.

**Edge cases testing this:**
- `edge-cases/anonymous-classes/nested-anonymous-runnable/`
- `edge-cases/anonymous-classes/anonymous-with-state/`

**Status:** Untested
**Evidence:** _(filled in Phase 10)_

## H8: J2K propagates JSR-305 / `@Nullable` / `@NotNull` annotations into Kotlin nullable / non-null types

**Claim.** When a Java method declares `@Nullable String foo()` (using `org.jetbrains.annotations.Nullable` or any well-known nullability annotation), J2K converts the return type to `String?`. The complementary `@NotNull` produces `String` (without the question mark) even where J2K would otherwise default to `String?`.

**Rationale.** This is the one piece of cross-source nullability inference J2K has had for a long time — it's the obvious win that keeps simple cases idiomatic. We expect this to work; if it doesn't, that's a *Refuted* finding that's itself useful.

**Edge cases testing this:**
- `edge-cases/nullability/jsr305-nullable/`

**Status:** Untested
**Evidence:** _(filled in Phase 10)_

## H9: J2K silently drops Java's `throws` declarations (since Kotlin has no checked exceptions)

**Claim.** A Java method declared as `void foo() throws IOException` becomes `fun foo(): Unit` — no `@Throws(IOException::class)` annotation, no `throws` clause (Kotlin has none), no comment marking the original exception contract.

**Rationale.** Kotlin has no checked exceptions, so the `throws` clause has nowhere to go. The "right" choice for Java interop would be `@Throws(IOException::class)` so the Kotlin function is callable from Java with the same checked-exception contract. Whether J2K emits this annotation is the falsifiable bit.

**Edge cases testing this:**
- `edge-cases/checked-exceptions/throws-ioexception/`
- `edge-cases/checked-exceptions/throws-multiple/`

**Status:** Untested
**Evidence:** _(filled in Phase 10)_

## H10: J2K's switch → when conversion loses fallthrough semantics

**Claim.** A Java `switch` with intentional fallthrough (cases without `break;`) is converted to a Kotlin `when` whose branches do **not** preserve the fallthrough behaviour — each branch executes independently.

**Rationale.** Kotlin's `when` does not have fallthrough as a syntactic feature. Reproducing the semantics requires duplicating the fallthrough cases' bodies into the earlier branch, which is a non-trivial transformation. We expect J2K to either (a) translate each branch independently (semantic regression!) or (b) refuse to convert and warn. Either is a reportable finding.

**Edge cases testing this:**
- `edge-cases/switch-statements/switch-fallthrough/`
- `edge-cases/switch-statements/switch-no-default/`

**Status:** Untested
**Evidence:** _(filled in Phase 10)_

## H11: J2K converts Java `synchronized` blocks/methods to Kotlin `synchronized(...) { }` calls

**Claim.** A Java `synchronized (lock) { … }` block becomes a Kotlin `synchronized(lock) { … }` function call (using the stdlib helper). A Java `synchronized` method becomes a Kotlin function whose body is wrapped in `synchronized(this) { … }`.

**Rationale.** The stdlib `synchronized` function exists for exactly this purpose. J2K is likely to use it — the conversion is straightforward. This hypothesis predicts the *idiomatic* outcome; refutation would be "J2K leaves it as `synchronized` modifier on the function, which doesn't exist in Kotlin and won't compile."

**Edge cases testing this:**
- `edge-cases/concurrency/synchronized-method/`
- `edge-cases/concurrency/synchronized-block/`

**Status:** Untested
**Evidence:** _(filled in Phase 10)_

## H12: J2K does not unwrap `java.util.Optional<T>` to Kotlin `T?`

**Claim.** A Java method returning `Optional<String>` becomes a Kotlin function returning `Optional<String>` (or `Optional<String?>`), not `String?`. Calls to `.isPresent()` / `.get()` / `.orElse(...)` are preserved verbatim instead of being collapsed to Kotlin null-safety idioms (`?.let`, `?:`, `!!`).

**Rationale.** `Optional` is a Java library type with no special status in J2K's type-system rules. Unwrapping it to a nullable would require a project-wide guarantee that no caller is using Optional's API beyond the get/orElse path — too aggressive for a static converter. We expect verbatim preservation.

**Edge cases testing this:**
- `edge-cases/nullability/optional-return/`
- `edge-cases/nullability/optional-map-chain/`

**Status:** Untested
**Evidence:** _(filled in Phase 10)_

---

## Index of edge cases by hypothesis

A reverse lookup: given a path in `edge-cases/`, which hypothesis does it test? See `edge-cases/README.md`.
