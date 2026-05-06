# Proposed J2K patch — emit Kotlin `override` modifier when Java `@Override` is present

This document accompanies the pipeline-side `OverrideFixup` pass (`eval/src/main/kotlin/.../OverrideFixup.kt`) shipped in Phase 12. The fixup is a downstream workaround; this doc identifies where the same fix would belong **upstream** in J2K itself, and what an upstream patch would look like.

> **Status**: this is a research-style proposal, not a submitted PR. The patch sketch below is based on reading `JetBrains/intellij-community` at branch `251` (the source of the IntelliJ Community 2024.1.7 we pin against). It hasn't been compiled or tested against an IntelliJ-platform dev build — that's outside our project's reach.

## 1. The bug — pattern + smallest reproduction

Across the curated edge-case dataset (Phase 8) and both real-world targets (spring-petclinic, OkHttp), J2K consistently produces Kotlin output where:

1. The Java source has a method annotated `@Override`.
2. The converted Kotlin output:
   - **keeps the `@Override` annotation as a literal Kotlin annotation** (which Kotlin tolerates — `@Override` resolves to `java.lang.Override` on JVM target — but it carries no semantic meaning), AND
   - **does not emit the Kotlin `override` modifier** on the function declaration.

The result is a Kotlin file that fails to compile with:

```
SourceFile.kt:N:M: '<methodName>' hides member of supertype '<SuperType>' and needs 'override' modifier
```

### Smallest reproduction (from our dataset)

`edge-cases/anonymous-classes/anonymous-with-state/source.java`:
```java
import java.util.function.IntSupplier;

public class CounterFactory {
    public IntSupplier create(int start) {
        return new IntSupplier() {
            private int count = start;
            @Override
            public int getAsInt() {
                count += 1;
                return count;
            }
        };
    }
}
```

J2K's converted output (from `build/reports/edge-cases/converted-pre-fixup/.../source.kt`):
```kotlin
class CounterFactory {
    fun create(start: Int): IntSupplier? {
        return object : IntSupplier() {
            private var count = start
            @Override
            fun getAsInt(): Int { ... }   // <-- @Override annotation kept; `override` modifier missing
        }
    }
}
```

Idiomatic Kotlin (what `expected.kt` shows):
```kotlin
class CounterFactory {
    fun create(start: Int): IntSupplier = object : IntSupplier {
        private var count = start
        override fun getAsInt(): Int { ... }   // <-- override modifier; no @Override
    }
}
```

## 2. Where it originates in J2K

Two collaborating sites in `JetBrains/intellij-community` at `plugins/kotlin/j2k/shared/src/org/jetbrains/kotlin/nj2k/`:

### a) `JavaToJKTreeBuilder.kt` — where the override flag should be set

When converting a Java `PsiMethod` to a JK function declaration, this class currently uses `findSuperMethods()` (a PSI resolution call) to detect overrides — see the `isOverrideMethodWithRedundantVisibility(...)` helper. This works fine when the Java PSI is fully resolved (e.g., when J2K is invoked from the IDE menu in a regular project context). But:

- In **anonymous-class** contexts, `findSuperMethods()` may return an empty set even when the user wrote `@Override` and the method genuinely overrides a SAM-interface method. The PSI-resolution path doesn't always cross the anonymous-class scope boundary cleanly.
- In **headless** contexts (our `j2k-runner` plugin's invocation pattern), Maven import isn't fully complete by the time J2K runs (see `memory/project_phase1_findings.md`), so `findSuperMethods()` returns empty for many methods that genuinely override.

In both cases, the override flag never gets set on the JK tree, and the Kotlin printer omits the `override` modifier.

### b) `JavaAnnotationsConversion.kt` — where the `@Override` annotation should be stripped

This conversion handles a small set of Java annotations (`@Deprecated`, `@Target`, `@Retention`, `@Repeatable`, `@Documented`, `@SuppressWarnings`) and translates each to a Kotlin equivalent. **`@Override` is not in the list** — so it survives unchanged into the Kotlin output, where it shows up as a literal annotation reference. Kotlin tolerates it (resolves to `java.lang.Override` on JVM target) but it does nothing useful.

These two omissions compound: the override modifier doesn't get set, AND the stale `@Override` annotation provides no fallback signal.

## 3. Proposed patch — sketch

The fix has two parts in two different files. Together they make J2K's headless invocation produce idiomatic Kotlin for the override case, with the same behavior as a fully-resolved IDE invocation.

### Patch 3a — `JavaToJKTreeBuilder.kt`

Use the presence of the Java `@Override` annotation as a fallback signal when `findSuperMethods()` returns empty. The user explicitly marked the method as overriding; respect that intent even when PSI resolution didn't confirm it.

```kotlin
// in JavaToJKTreeBuilder.kt, where the JK function declaration is built:

private fun PsiMethod.detectOverrideKind(): Boolean {
    // Existing path — works when PSI is fully resolved.
    if (findSuperMethods().isNotEmpty()) return true

    // NEW: fallback to the user-declared intent. This catches:
    //   - anonymous classes whose enclosing scope confuses findSuperMethods
    //   - headless / partially-resolved contexts (e.g., headless ApplicationStarter
    //     invocations where the Maven import hasn't fully completed)
    return modifierList.annotations.any { it.qualifiedName == "java.lang.Override" }
}
```

Then call `detectOverrideKind()` instead of `findSuperMethods().isNotEmpty()` at every site that decides whether to set the JK override flag on the function being built.

### Patch 3b — `JavaAnnotationsConversion.kt`

Add `@Override` to the list of annotations that get stripped during conversion. Kotlin's `override` modifier supersedes it; keeping the annotation produces noise without semantic value.

```kotlin
// in JavaAnnotationsConversion.kt, in the annotation-handling table:

"java.lang.Override" -> {
    // Drop the annotation entirely. The override semantics are carried by the
    // Kotlin `override` modifier (set in JavaToJKTreeBuilder via the new
    // detectOverrideKind path). Leaving @Override here would resolve to
    // java.lang.Override on JVM target but do nothing useful.
    annotation.delete()
    return@processAnnotation true
}
```

## 4. Why we believe the post-processor would normally catch this

The IntelliJ IDE menu version of J2K runs **after** a fully-imported Java project context, where:
- `findSuperMethods()` returns the right answer because the project model knows about the supertypes.
- A separate post-processing pass (driven by `KotlinCacheServiceImpl.createFacadeForFilesWithSpecialModuleInfo`, invoked through `NewJ2kPostProcessor`) runs inspections that include "this method needs an override modifier" auto-fixes.

Both of those mechanisms break in our headless invocation:
- The headless application context doesn't fully resolve Maven dependencies before J2K runs (documented in `memory/project_phase1_findings.md`).
- `KotlinCacheServiceImpl.createFacadeForFilesWithSpecialModuleInfo` throws `IllegalArgumentException: Collection has more than one element` non-fatally during every conversion — captured in our pipeline's IDE log artifacts.

So the **direct cause** of the missing `override` is an IDE-internals issue we can't easily address from outside the IntelliJ source tree. The patch sketched in §3 makes J2K's behavior **robust to the cases where the post-processor doesn't run** by adding a pre-emptive fallback at the JK-tree-builder level. The fallback is conservative — it only fires when the user explicitly wrote `@Override` — so it can't introduce false positives.

## 5. Test case

The patch is validated by `edge-cases/anonymous-classes/anonymous-with-state/` from our dataset. With the upstream patch applied, the converted Kotlin would be:

```kotlin
class CounterFactory {
    fun create(start: Int): IntSupplier? {
        return object : IntSupplier() {            // (note: parens-on-interface is a separate
                                                   //  J2K bug not addressed by this patch)
            private var count = start
            override fun getAsInt(): Int {         // <-- now has the modifier; @Override removed
                count += 1
                return count
            }
        }
    }
}
```

A subset of the kotlinc errors that block this case from compiling would be resolved — specifically the `'getAsInt' hides member of supertype 'IntSupplier' and needs 'override' modifier` error. Other errors (the parens-on-interface and missing-`override` on `equals` due to wrong parameter type) remain and would need separate fixes — they're documented in the same commit's `docs/edge-cases-report.md` Phase-12 impact section.

## Companion: pipeline-side fixup (what we ship)

While the upstream patch above is the *correct* fix, we don't have direct access to commit it to JetBrains' repo. So our project ships the **`OverrideFixup`** pass (`eval/src/main/kotlin/.../OverrideFixup.kt`) — a downstream workaround that:

1. Compiles every converted `.kt` via the embedded `K2JVMCompiler`.
2. Filters errors matching the `"needs 'override' modifier"` diagnostic.
3. For each, prepends `override ` to the function declaration line and removes the now-redundant `@Override` annotation immediately above.
4. Re-compiles to verify the fix worked.

This is text-level rather than PSI-rewrite because `kotlinc` already pinpoints the exact line — no need for type resolution or PSI walking. Net effect on our edge-case dataset (see `docs/edge-cases-report.md` Phase-12 impact section): **6 fixups applied across 4 files; 5 compile errors resolved**. The verdict counts don't change (Pass=5, Partial=10, Fail=10) because the affected cases also have other bugs (the parens-on-interface bug, the `Object` vs `Any?` mismatch in `equals`) that prevent full compilation. But the fixup is real signal that the override bug exists at scale and the patch above would resolve it.
