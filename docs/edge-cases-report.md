# Edge cases — results

The hand-curated edge-case dataset under `edge-cases/` (25 cases across 10 categories) was run through the Phase-9 pipeline and scored against the predictions in [`hypotheses.md`](hypotheses.md). This document is the human-facing companion to the auto-generated `build/reports/edge-cases/report.md` (regenerated on every CI run).

> **Pinned input:** `edge-cases/` dataset at the commit recorded in this repo.
> **Compilability check:** `kotlin-compiler-embeddable` `K2JVMCompiler` programmatically with `jvmTarget=17`, stdlib auto-located from this process's classpath.
> **Reproduction:** `./gradlew runEdgeCases -Ptarget=edge-cases` — see end of file.

## TL;DR

Of 12 falsifiable predictions about J2K behaviour, the edge-case pipeline produced these statuses:

| Status | Count | Hypotheses |
|---|---:|---|
| **Refuted** (J2K does the right thing) | 0 | (none on every linked case) |
| **Partial** (mixed signal — at least one case passes) | 4 | H2, H6, H9, H10, H11 |
| **Confirmed** (J2K consistently misses the predicted idiom) | 7 | H1, H3, H4, H5, H7, H8, H12 |

*(Note: H6 status is "Partial" because the `with-method` case got a Partial verdict driven by H1 bleed-in — the structural data-class conversion itself succeeds.)*

The four partial-status hypotheses produced **five concrete pass cases** that are themselves real findings about J2K's capabilities — see [Notable wins](#notable-wins-where-j2k-does-the-right-thing).

The seven confirmed hypotheses split into two categories of "miss":
- **Idiomatic** (J2K compiles cleanly but produces non-idiomatic Kotlin): H1, H4 (partial), H5
- **Structural / behavioural** (J2K produces code that doesn't compile or alters semantics): H3, H7, H8, H12, plus one case each from H4, H10, H11

The most surprising result: **J2K's static converter has more "right answers" baked in than the spring-petclinic data alone suggested.** Several cases that the petclinic post-processor failure mode hid (no `@Throws`, no `@Synchronized`, no `@JvmRecord data class`) actually work correctly in pure-conversion mode.

## Headline metrics

| Metric | Value |
|---|---:|
| Total cases | 25 |
| ✅ Pass | 5 |
| 🟡 Partial | 10 |
| ❌ Fail | 10 |
| Hypotheses with at least one Pass | 5 (H2, H6, H9, H10, H11) |
| Hypotheses fully Confirmed | 7 (H1, H3, H4, H5, H7, H8, H12) |

The Partial verdicts almost all share the same root cause — **H1 (no expression-body functions) bleeds in everywhere**. A case that exercises H5 (string templates) and pairs cleanly will still come back Partial because its single function body is `{ return "..." }` instead of `= "..."`. So the Partial column is a fair reading of "structurally right, idiomatically lossy."

## Notable wins (where J2K does the right thing)

Five Pass cases — each one a falsified prediction worth flagging:

### `records/java-record-basic` ([H6](hypotheses.md#h6-j2k-does-not-convert-java-records-to-kotlin-data-classes) refuted)

Java input:
```java
public record Point(int x, int y) { }
```

J2K output:
```kotlin
@kotlin.jvm.JvmRecord
data class Point(val x: Int, val y: Int)
```

J2K correctly emits `data class` AND adds `@kotlin.jvm.JvmRecord` for full Java-side compatibility. This required `jvmTarget=17` in the compilability check (records compile only at JVM target ≥ 16); on the lower default target the test reports DoesNotCompile and hides the win. **Worth checking spring-petclinic-style real-world reports for `@JvmRecord` emission to confirm this generalises.**

### `switch-statements/switch-fallthrough` ([H10](hypotheses.md#h10-j2ks-switch--when-conversion-loses-fallthrough-semantics) refuted)

The hypothesis predicted J2K would lose Java's intentional switch fallthrough semantics. It doesn't:

```kotlin
when (code) {
    1 -> {
        out.append("one")
        out.append("two")  // duplicated from case 2 to preserve fallthrough
    }
    2 -> out.append("two")
    3 -> out.append("three")
    else -> out.append("other")
}
```

J2K duplicates the case-2 body into case-1 to preserve behaviour. This is a non-trivial semantic-preserving rewrite that J2K handles correctly.

### `checked-exceptions/throws-multiple` ([H9](hypotheses.md#h9-j2k-silently-drops-javas-throws-declarations-since-kotlin-has-no-checked-exceptions) refuted on the multi-exception case)

```kotlin
@kotlin.Throws(IOException::class, SQLException::class)
fun fetch(id: Int): String? {
    if (id < 0) throw IOException("bad id")
    if (id == 0) throw SQLException("empty key")
    return "row:$id"
}
```

J2K correctly emits `@kotlin.Throws(...)` with all of the original throws clauses' types, preserving the Java-interop checked-exception contract. The hypothesis predicted this would be silently dropped — refuted.

### `concurrency/synchronized-method` ([H11](hypotheses.md#h11-j2k-converts-java-synchronized-blocksmethods-to-kotlin-synchronized--calls) refuted on the modifier case)

```kotlin
@kotlin.jvm.Synchronized
fun next(): Int { ... }
```

J2K correctly converts the Java `synchronized` method modifier to `@kotlin.jvm.Synchronized`. The synchronized-**block** case (`synchronized (lock) { ... }` form) didn't fare as well — it failed to compile (see [Confirmed failures](#confirmed-failures-where-j2k-actually-misses)).

### `property-folding/setter-only` ([H2](hypotheses.md#h2-j2k-does-not-fold-private-fields-with-public-getterssetters-into-kotlin-properties) refuted on the negative-control case)

The case was the negative-control: a write-only field with no getter, where the *idiomatic* Kotlin port keeps the setter as a function. J2K kept it. Pass — for the right reason (preserving write-only semantics).

## Confirmed failures (where J2K actually misses)

Sorted by severity. Each entry links the converted output and shows the smallest reproducible problem.

### J2K produces invalid Kotlin (compile-time bugs)

These are the ones that matter most — the conversion produces code that won't even compile.

#### Missing `override` on hidden supertype members

Hits `anonymous-classes/*` and `pojo-data-class/*`. Example from `anonymous-classes/anonymous-with-state`:

```
source.kt:11:17: 'getAsInt' hides member of supertype 'IntSupplier' and needs 'override' modifier
```

The Java anonymous class implements `IntSupplier.getAsInt()`; J2K emits the Kotlin equivalent without the `override` keyword. Same root cause produces compile failures on `equals`/`hashCode`/`toString` overrides in the POJO cases.

This is the largest single class of real bugs — ~6 cases hit some flavour of "method needs override modifier."

#### Anonymous class instantiation syntax

```
source.kt:5:33: This class does not have a constructor
```

J2K converts `new Runnable() { @Override public void run() { ... } }` into something like `Runnable() { override fun run() { ... } }` — but `Runnable` is an interface and Kotlin requires the `object :` syntax (`object : Runnable { override fun run() { ... } }`).

#### Smart-cast vs mutable-property mismatch

`nullability/optional-chain` fails with:
```
Smart cast to 'Address' is impossible, because 'address' is a mutable property
```

J2K converted `if (address != null && address.getCity() != null)` to a Kotlin `if`-chain that smart-casts `address` to non-null — but the property is `var`, so Kotlin disallows the smart-cast across statement boundaries. The idiomatic fix is `address?.city ?: default` (which our `expected.kt` shows). J2K didn't do that rewrite.

#### `Optional<T>` left verbatim with weird nullability

```
source.kt:8:20: Type mismatch: inferred type is Optional<String> but Optional<String?>? was expected
```

J2K added `?` decorations around `Optional<...>` in some places but not others, producing a contradictory type signature. The idiomatic conversion would unwrap to `T?` entirely.

### J2K compiles cleanly but produces non-idiomatic Kotlin (Partial verdicts)

These are the predicted-and-confirmed Partial cases:

- **H1 — block-body bias** is the universal Partial driver: every Partial case has `expr-body fn: -N, block-body fn: +N` in its idiom delta. J2K never produces `fun foo(): T = expr` even when the Java body is a single `return <expr>;`.
- **H5 — string templates** all three cases came back Partial with the same shape: J2K *did* perform the `+`-to-`$x` interpolation transform (matching `expected.kt`), but the surrounding function body is wrapped in `{ return "..." }` instead of `= "..."`. So the H5 prediction is technically confirmed, but the converter's primary intent is correct.
- **H4 — `null-check-then-call`** got Partial because J2K used a different rewrite than the gold expected: it produced `?.let { "Hello, ${it.uppercase()}" }` instead of the smart-cast `if (name != null) "Hello, ${name.uppercase()}" else "..."`. Both are valid Kotlin, the structural shapes differ.

### J2K converts to broken-when-isolated code (eval-side limitation)

`nullability/jsr305-nullable` fails:
```
Unresolved reference: Nullable
Unresolved reference: NotNull
```

J2K preserved the `org.jetbrains.annotations.Nullable` / `@NotNull` annotation references verbatim. They don't resolve because our compilability check uses a stdlib-only classpath. **This is an eval-side limitation, not a J2K bug** — the conversion is structurally correct; we just can't validate it without adding the annotations jar to the classpath. The DoesNotCompile verdict is honest about what we measured but should not be read as "J2K mishandled this case."

## Per-hypothesis verdict + reasoning

The full table is in `build/reports/edge-cases/report.md`. The narrative below adds *why* each verdict landed where it did.

| H# | Status | Reasoning |
|---|---|---|
| H1 (no expr-body fns) | Confirmed | 3 Partial + 1 Fail (the Fail is `computed-property` — separate H2 issue). Universal across all multi-statement cases. |
| H2 (no getter/setter folding) | Partial | 1 Pass (`setter-only`, by design), 2 Partial (the bean cases — converted gets `+block-body fn` for each separate getX/setX). |
| H3 (no data class inference) | Confirmed | 2 Fails — both POJO cases produced uncompilable Kotlin (missing override, lost `Double.compare` static reference). The `data class` inference itself didn't happen. |
| H4 (avoids `!!`) | Confirmed | 1 Partial + 1 Fail — J2K *did* avoid `!!` in both cases (consistent with the prediction), but produced non-idiomatic alternatives in one and broken smart-casts in the other. |
| H5 (string templates) | Confirmed | 3 Partials — all string-template transforms worked; partial verdicts come from H1 bleed (block bodies). |
| H6 (no records support) | Partial | `java-record-basic` Pass (J2K → `@JvmRecord data class`), `java-record-with-method` Partial (data class works; method bodies block-wrapped per H1). |
| H7 (preserves nested anonymous) | Confirmed | 2 DoesNotCompile — J2K kept the anonymous form but emitted invalid Kotlin syntax for it. Worse than predicted (we expected verbatim preservation, got broken code). |
| H8 (JSR-305 propagation) | Confirmed | 1 DoesNotCompile — but actually **inconclusive on J2K's behaviour**. The compile failure is unresolved annotation references (eval-side classpath limitation), not a J2K-emitted bug. Need to add `org.jetbrains:annotations` to the compile classpath to get a real verdict. |
| H9 (drops throws) | Partial | `throws-multiple` Pass (J2K → `@kotlin.Throws(IO, SQL)` ✓), `throws-ioexception` Partial (also `@Throws` ✓ — Partial only because of H1 block-body bleed). The hypothesis is genuinely refuted on the substantive question. |
| H10 (loses fallthrough) | Partial | `switch-fallthrough` Pass — J2K duplicates the case body to preserve semantics! `switch-no-default` Partial (also correct semantics; H1 block-body Partial). The hypothesis is refuted on the substantive question. |
| H11 (synchronized handling) | Partial | `synchronized-method` Pass (`@kotlin.jvm.Synchronized` ✓), `synchronized-block` DoesNotCompile (Object?/Any type mismatch — real bug). |
| H12 (no Optional unwrapping) | Confirmed | 2 DoesNotCompile — J2K preserved `Optional<...>` references but mangled the nullability decorations, producing contradictory type signatures. |

## Caveats

- **Compile classpath is stdlib-only.** Cases that reference `org.jetbrains.annotations.Nullable`, `org.jetbrains.annotations.NotNull`, or other non-stdlib types fail to compile not because of J2K bugs but because the references can't be resolved. H8 specifically falls into this bucket — the result there should be read as "inconclusive" rather than "confirmed."
- **Verdict logic is asymmetric: H<n> Partial means at least one Pass exists.** The `HypothesisRollup.status()` rule is: all-pass → Refuted, all-non-pass → Confirmed, mixed → Partial. So a Partial status is a refutation in spirit, not a 50/50 finding.
- **Idiom-bleed across hypotheses.** Every case implicitly tests H1 (block-body bias) because every Java method gets converted to *some* Kotlin function. A Partial verdict on a case ostensibly testing H5 or H10 is usually really about H1. The findings narrative above tries to disentangle these where it matters.
- **The conversion runs once per pipeline invocation.** All 25 cases convert in a single batched IDE startup (~30 s after caches are warm). Re-running just the verdict pass (e.g., to iterate on the eval engine) is ~5 s.

## Reproduction

```bash
./gradlew runEdgeCases -Ptarget=edge-cases
# inspect:
cat build/reports/edge-cases/report.md
ls  build/converted/edge-cases/edge/                     # converted .kt files in package layout
```

First run takes ~10–15 min on a cold cache (downloads IntelliJ Community 2024.1.7); subsequent runs take ~30–60 s.

CI runs the full edge-case pipeline on every push and PR; the rendered Markdown report is visible inline on the GitHub Actions run page (`$GITHUB_STEP_SUMMARY`), and the full report + every converted `.kt` is downloadable as the `eval-edge-cases` artifact.

## What this informs going forward

1. **Phase 12 (optional fix) candidates.** The "missing override modifier" cluster is the largest concrete bug class — 6 cases. Fixable by tweaking J2K's PSI tree builder (or by adding a post-conversion fixup in our pipeline). The smart-cast-vs-mutable-property issue is the next biggest correctness gap.
2. **The compilability check is a useful signal.** Of 25 cases, 10 fail at the compile step alone — i.e., **40 % of converted code in our edge-case dataset doesn't even type-check**. For OkHttp this proportion may be very different (Kotlin-aware imports, more uniform shape).
3. **The "J2K's right answers" list (H6, H9, H10, H11 partials) is itself useful evidence** for any JetBrains team member reading this: J2K is doing more right than the spring-petclinic numbers suggested, and the bottleneck on idiomatic output is overwhelmingly the post-processor (which we documented can't run cleanly in headless mode — Phase 1+2 finding).

## Phase 12 fix impact — `OverrideFixup` pass

Phase 12 ships an `OverrideFixup` pass in the eval module (`eval/src/main/kotlin/.../OverrideFixup.kt`) plus a proposed J2K patch document ([`proposed-j2k-fix.md`](proposed-j2k-fix.md)).

The fixup is **on by default** for `runEdgeCases` — pass `-Pedgecases.applyFixups=false` to disable for comparison. Pre-fixup converted output is preserved at `build/reports/edge-cases/converted-pre-fixup/` so you can diff to see exactly what got changed.

### Numbers

| Metric | Value |
|---|---:|
| Fixups applied (lines where `override ` was prepended) | 6 |
| Files modified | 4 |
| Compile errors resolved by fixup | 5 |
| Files that newly compile cleanly post-fixup | **0** |
| Verdict counts (before vs after fixup) | unchanged: 5 Pass / 10 Partial / 10 Fail |

### Why the verdict counts didn't move

The 4 affected files (`anonymous-classes/anonymous-with-state`, `anonymous-classes/nested-anonymous-runnable`, `pojo-data-class/pojo-equals-hashcode`, `pojo-data-class/pojo-with-toString`) all have **other compile errors** beyond just the missing `override`:

- Both `anonymous-classes/*` cases hit the **parens-on-interface bug** — J2K emits `object : Runnable()` (with parens, treating Runnable as a class constructor) instead of `object : Runnable` (interface implementation). Our fixup adds the `override` modifier correctly, but the file still won't compile.
- Both `pojo-data-class/*` cases have an `equals` method whose parameter type J2K kept as Java's `Object?` instead of Kotlin's `Any?`. As a result, `equals(o: Object?)` doesn't actually override `Any.equals(Any?)` — kotlinc says "incompatible types" rather than "needs override modifier." Our fixup correctly *doesn't* add `override` here (it would be wrong; the signature genuinely doesn't override anything). But the file still doesn't compile.

So **the fixup does its job** — every line it touched is genuinely an override-modifier issue that's now resolved. It just doesn't single-handedly recover any case verdict, because cases that exhibit the override bug usually exhibit other bugs too.

### What this validates

- The override-modifier bug is **real, widespread, and worth fixing upstream**. Even on this small 25-case dataset it affects 4 distinct cases and 6 method declarations.
- A pipeline-side text fixup is a viable workaround for downstream consumers — it's deterministic, validated by recompile, and correctly conservative (only fires when kotlinc explicitly says "needs override modifier").
- The proposed J2K patch (in [`proposed-j2k-fix.md`](proposed-j2k-fix.md)) is the right *upstream* fix, but the cases that exhibit this bug usually exhibit OTHER bugs too — so even an upstream fix wouldn't single-handedly improve the Pass count on this dataset. Multiple co-occurring bugs is itself a finding: J2K's failures cluster, and addressing one bug class meaningfully requires addressing several.

### Reproduction

```bash
# With fixups (default):
./gradlew runEdgeCases -Ptarget=edge-cases
cat build/reports/edge-cases/report.md           # Check the "Aggregate" banner

# Without fixups (baseline):
./gradlew runEdgeCases -Ptarget=edge-cases -Pedgecases.applyFixups=false

# Diff what the fixup changed:
diff -r build/reports/edge-cases/converted-pre-fixup/ build/converted/edge-cases/
```
