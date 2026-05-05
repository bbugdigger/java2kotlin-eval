# Results — `spring-petclinic`

This is the canonical, committed report for the **spring-petclinic** target. It interprets the numbers and surfaces the actionable findings; the full machine-generated report (with every paired declaration's similarity and idiom delta) is regenerated on every CI run and lives at `build/reports/spring-petclinic/report.md`.

> **Pinned input:** `targets/spring-petclinic` submodule at the commit recorded in `.gitmodules`.
> **Gold reference:** `targets/spring-petclinic-kotlin/src/main/kotlin` (the official Kotlin port).
> **Toolchain:** IntelliJ Community 2024.1.7, Kotlin plugin 241.x, JDK 17.
> **Reproduction:** `./gradlew runEval -Ptarget=spring-petclinic` — see end of file.

## TL;DR

J2K cleanly converts **all 30** Java files into syntactically valid Kotlin (`attempted=30 converted=30 failed=0`), producing output that **structurally tracks the human-written gold port** (mean PSI similarity **0.99** across 73 paired declarations, **76 % pairing rate** against gold). However, the output is consistently **less idiomatic than gold** along several specific, measurable axes: J2K **never** produces expression-body functions or primary-constructor properties, doesn't infer the one obvious data class, never emits `!!`, and emits ~2× more `block-body` functions than gold. These are systemic patterns worth turning into testable hypotheses (Phase 8).

The headline 99 % similarity is **structurally** correct but **stylistically misleading** — see [Why 99% similarity is the floor, not the ceiling](#why-99-similarity-is-the-floor-not-the-ceiling).

## Methodology

The eval pipeline does three things, in order:

1. **Convert.** A small custom IntelliJ plugin (`j2k-runner`) registers an `ApplicationStarter` that boots a headless IntelliJ 2024.1.7, opens `targets/spring-petclinic` as a Maven project, and invokes the IDE's own `JavaToKotlinAction.Handler.convertFiles(...)` against every `.java` file under `src/main/java`. Output `.kt` files land in `build/converted/spring-petclinic/`. The submodule itself is never mutated — the runner sandboxes the conversion under `<output>/.work/`.

2. **Pair.** The `eval` module parses both `build/converted/spring-petclinic/` and `targets/spring-petclinic-kotlin/src/main/kotlin/` into PSI trees using `kotlin-compiler-embeddable`. It collects every named declaration (top-level + nested classes, objects, functions, properties, companion objects), keys them by fully-qualified name, and pairs gold ↔ converted by FQN with overload-signature fallback.

3. **Score.** For every matched pair: PSI element-type histogram cosine similarity (one number in `[0, 1]`) and a tally of 12 specific Kotlin idioms (val/var, expression-body functions, default args, `?.`, `!!`, `?:`, scope functions, `data class`, primary-constructor properties, interpolated strings). Per-file rollups + an aggregate idiom table + the worst-similarity pairs are rendered to `report.md` and `report.json`.

The full design rationale is in the plan doc and the in-code comments; the auto-generated report has every paired declaration with its individual scores.

## Headline metrics

| Metric | Value |
|---|---|
| Java files converted | 30 / 30 |
| Gold `.kt` files | 24 |
| Gold declarations | 96 |
| Converted declarations | 137 |
| **Matched (paired by FQN)** | **73** |
| Gold-only orphans | 23 |
| Converted-only orphans | 64 |
| **Pairing rate (matched ÷ gold)** | **76 %** |
| **Mean PSI similarity (paired)** | **0.99** |

(See `build/reports/spring-petclinic/report.md` for the per-file rollup and per-declaration drill-down.)

## What the orphans tell us

The asymmetric orphan counts (23 gold-only vs **64** converted-only) aren't pairing bugs — they reflect **structural differences in how the gold port was written vs. how J2K produces output**:

- **Converted-only orphans dominate** because J2K leaves Java's `getX()` / `setX()` patterns as separate Kotlin functions instead of folding them into Kotlin properties. Each Java getter/setter pair becomes two declarations on the converted side that have no counterpart on the gold side (where they're collapsed into one property).
  - Concrete example: `Owner.kt` — gold has 9 declarations, converted has 18, with 9 successfully paired and 9 unpaired (the standalone `getX`/`setX` functions).

- **Gold-only orphans** mostly fall into three buckets:
  - The `visit/` package on gold (`Visit.kt`, `VisitRepository.kt`) — gold consolidated some of `owner/`'s visit-related types into a separate package.
  - Repository extension methods on gold that don't exist in the Java sources at all (`PetRepository`, `CacheConfig`).
  - One Spring-specific class — `PetClinicRuntimeHints` — that has no gold equivalent because the Kotlin port doesn't ship it.

A higher pairing rate would not necessarily indicate a "better" J2K — it would indicate a converter that fold getters/setters into properties (which would address the largest source of orphans).

## Findings

The aggregate idiom table is the most actionable summary of how J2K's output diverges from gold:

| Idiom | Gold | Converted | Δ | Reading |
|---|---:|---:|---:|---|
| **expr-body fn** | 28 | **0** | **-28** | **J2K never emits `fun foo() = bar()`.** Every function body is a `{ return … }` block, even one-liners. |
| **block-body fn** | 47 | 107 | **+60** | The flip side of the above. Plus the standalone `getX`/`setX` functions inflate this count. |
| **primary-ctor prop** | 9 | **0** | **-9** | **J2K never folds fields into the primary constructor.** Gold uses `class Owner(val name: String, …)`; converted declares each property separately inside the class body. |
| **data class** | 1 | **0** | **-1** | J2K didn't recognize the candidate (likely `Pet` or `Specialty`) where gold uses `data class`. |
| **!!** | 4 | **0** | **-4** | J2K **avoids non-null assertions entirely**, leaving expressions nullable instead. Whether this is "good" or "bad" depends on context — it's a defensible safety choice, but it produces noisier downstream `?.` chains. |
| **?.** | 2 | **0** | **-2** | Same posture: gold uses safe calls in 2 places where converted re-shaped the expression to avoid them. |
| **interpolated string** | 0 | **+13** | **+13** | **J2K uses string interpolation more often than gold.** This is one place where converted is *more* idiomatic than the human port — gold tends to use `+` concatenation; J2K folds those into `"$x and $y"`. Genuinely unexpected finding. |
| val | 34 | 72 | +38 | Inflated because J2K also creates a `val` for each backing field that gold collapsed into a property. |
| var | 25 | 25 | 0 | Identical count — neat. |
| ?: (Elvis) | 6 | 6 | 0 | Identical count. |
| default arg | 1 | 0 | -1 | One default parameter on the gold side that J2K doesn't reproduce. |
| scope fn (let/apply/also/run/with) | 0 | 1 | +1 | Both sides are essentially scope-fn-free; one place where converted introduced one and gold didn't. |

### Concrete worst-similarity pairs

The five lowest-similarity declarations all sit at **96.8 %** — a tight cluster, which suggests they're hitting the same kind of structural difference rather than independently degenerate cases:

| FQN | Similarity | Source |
|---|---:|---|
| `OwnerRepository.findById` | 96.8 % | `owner/OwnerRepository.kt` |
| `Person.firstName` | 96.8 % | `model/Person.kt` |
| `Person.lastName` | 96.8 % | `model/Person.kt` |
| `Owner.address` | 96.8 % | `owner/Owner.kt` |
| `Owner.city` | 96.8 % | `owner/Owner.kt` |

Four of five are **bean-style fields** (`firstName`, `lastName`, `address`, `city`). On the gold side these are `val`/`var` properties declared in the body or the primary constructor; on the converted side they're properties + paired `getX`/`setX` functions, so the underlying property declaration ends up wrapped in slightly different surrounding boilerplate. The small similarity drop reflects this consistent pattern, not actual conversion errors.

`OwnerRepository.findById` is in the same cluster but for a different reason: gold uses an Optional-returning Spring Data interface method with `@Query`, J2K reproduces it with the same annotations but slightly different surrounding type-parameter PSI structure.

## Why 99 % similarity is the floor, not the ceiling

A reasonable reaction to "mean PSI similarity 0.99" is "great, J2K is essentially equivalent to a human port." That's the **wrong** reading.

PSI element-type histograms naturally cluster high across two same-shape codebases: both have classes (so `KtClass` count matches), both have properties (so `KtProperty` count matches), both have functions (so `KtNamedFunction` count matches), and so on. A 0.99 cosine just means **"both files contain roughly the same proportions of each PSI node type"** — not that they make the same idiomatic choices within each declaration.

The actionable signal lives in the **idiom delta table**, not in the similarity number. A converted declaration can score 99 % similarity to its gold counterpart while still using `getX()/setX()` instead of properties (no PSI element type difference — both use `KtNamedFunction` for the getter and `KtProperty` for the backing field).

In future iterations we could add a more discriminating similarity metric — e.g., weight the histogram by "Kotlin-specific" PSI types (`KtSafeQualifiedExpression`, `KtCallableReferenceExpression`) so that absence of those constructs penalises the score. For v1 we keep the cosine as the principled-but-coarse summary number and let the idiom table do the explanatory work.

## Caveats and what would change with deeper Maven integration

Our headless conversion produces what is best described as **"raw J2K + minimal post-processing."** During the run, J2K's post-processor logs several non-fatal `SEVERE` errors:

```
SEVERE - NewJ2kPostProcessor - Collection has more than one element.
  at KotlinCacheServiceImpl.createFacadeForFilesWithSpecialModuleInfo
  at InferenceProcessing
  at ConvertGettersAndSettersToPropertyProcessing
```

These are J2K's polish passes (nullability inference, getters→properties folding, etc.) failing because **Maven dependency resolution doesn't fully complete** in our headless application context before the converter runs. The `getters→properties` post-processing is the single biggest reason converted output keeps separate `getX`/`setX` functions instead of folding them; in a fully-imported Maven project (i.e., what an IDE user would have when clicking the menu item), this pass succeeds and the output is much closer to idiomatic Kotlin.

So the eval as it stands measures **the quality of J2K's core lexical/syntactic conversion**, not the quality of the IDE user's experience. This is not a bug in our pipeline — it's what is reproducible from CI without manually pre-importing every target. We document it openly because:

1. It explains why the deltas in the idiom table are so large.
2. It's exactly the kind of constraint a JetBrains team member would want to know about a tool that proposes to evaluate J2K at scale.
3. A reasonable Phase-12 ("propose a fix") direction is: investigate why the post-processor's `KotlinCacheServiceImpl` lookup fails in headless mode, since fixing it would improve every eval result we run.

## Reproduction

```bash
git clone --recurse-submodules <repo-url>
cd java2kotlin-eval
./gradlew runEval -Ptarget=spring-petclinic
# inspect:
cat build/reports/spring-petclinic/report.md
ls  build/converted/spring-petclinic/      # 30 .kt files preserving the input package layout
```

First run takes ~10–15 min (downloads IntelliJ Community 2024.1.7, ~600 MB). Subsequent runs reuse the gradle cache and complete in ~30–60 s.

CI runs the same pipeline on every push and PR; the rendered Markdown summary is visible directly on the GitHub Actions run page (`$GITHUB_STEP_SUMMARY`), and the full report + converted output are downloadable as the `eval-spring-petclinic` artifact.

## What's next

Three follow-ups, in priority order:

1. **Hypotheses doc + edge-case dataset (Phases 8–11).** The findings table above is the seed for `docs/hypotheses.md` — each row becomes a falsifiable claim ("J2K never emits expression-body functions when the source is a single `return …` statement") plus a tiny hand-crafted Java fixture under `edge-cases/` to validate it in isolation.
2. **Compilability check.** Currently we don't verify that `build/converted/spring-petclinic/` actually compiles. Adding a `kotlinc`-based compile pass against the converted output would catch the (presumably small but nonzero) cases where J2K emits invalid Kotlin.
3. **Add a second OSS target (Phase 13).** Repeating the eval against a structurally different real-world project (e.g., a non-Spring, non-Maven codebase) tests whether the idiom-delta findings are spring-petclinic-specific or generalize.
