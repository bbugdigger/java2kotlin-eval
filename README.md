# java2kotlin-eval

[![j2k-eval](https://github.com/bbugdigger/java2kotlin-eval/actions/workflows/j2k-eval.yml/badge.svg)](https://github.com/bbugdigger/java2kotlin-eval/actions/workflows/j2k-eval.yml)

A reproducible CI pipeline that drives JetBrains' static **J2K** (Java → Kotlin) converter against real-world OSS Java projects, scores the output against hand-written "gold" Kotlin ports using PSI structural analysis, validates a hypothesis-driven edge-case dataset against the converter, and ships a downstream fixup pass for one identified J2K bug.

## What this is

Two cooperating Gradle modules driving a single command:

1. **`j2k-runner`** — a tiny IntelliJ plugin that registers an `ApplicationStarter`. Boots a headless IntelliJ Community 2024.1.7, opens the target as a Maven project, and invokes `JavaToKotlinAction.Handler.convertFiles(...)` — the IDE's own conversion entry point — against every `.java` under the source root.
2. **`eval`** — a Kotlin executable that:
   - parses both the converted output and a "gold" Kotlin port using `kotlin-compiler-embeddable`
   - pairs declarations by FQN (with overload-signature fallback)
   - emits a four-section Markdown report + machine-readable JSON
   - scores via PSI element-type histogram cosine similarity (one principled per-pair number) + counts of 12 specific Kotlin idioms (val/var, expression-body fns, scope fns, `?.`, `!!`, `data class`, primary-ctor properties, …)
   - runs a per-case **Pass / Partial / Fail** verdict engine over a curated edge-case dataset (`edge-cases/`) tagged against falsifiable hypotheses in `docs/hypotheses.md`
   - applies an `OverrideFixup` pass that uses kotlinc diagnostics to repair one specific J2K bug (missing `override` modifier on hidden supertype members) — see [`docs/proposed-j2k-fix.md`](docs/proposed-j2k-fix.md) for the upstream patch we propose for the same bug

Three commands cover everything:

```bash
./gradlew runEval -Ptarget=spring-petclinic    # convert + score the petclinic Java tree vs the official Kotlin port
./gradlew runEval -Ptarget=okhttp              # same against square/okhttp 3.14.2 (Java) vs 4.12.0 (Kotlin)
./gradlew runEdgeCases -Ptarget=edge-cases     # convert + verdict the curated 25-case dataset
```

The output of each:
- `build/converted/<target>/**.kt` — J2K's actual conversion output
- `build/reports/<target>/report.md` — human-readable scorecard
- `build/reports/<target>/report.json` — machine-readable scorecard

## Headline results

### `spring-petclinic` — small Spring Boot sample, 30 Java files

```
attempted=30 converted=30 failed=0
matched=73 / 96 gold declarations  (76% pairing rate)
mean PSI similarity (paired) = 0.99
```

J2K converts every file cleanly and tracks the gold port's overall structure, but **systematically diverges** along measurable idiom axes — never produces expression-body functions (-28 vs. gold), never folds fields into primary-constructor properties (-9), avoids `!!` entirely (-4). Narrative: [`docs/results-spring-petclinic.md`](docs/results-spring-petclinic.md).

### `okhttp` — Square's HTTP client, 103 Java files (`square/okhttp` @ 3.14.2 vs 4.12.0)

```
attempted=103 converted=103 failed=0
matched=1936 / 2579 gold declarations  (75.1% pairing rate)
mean PSI similarity (paired) = 0.987
```

Same converter, ~19× the surface area. Confirms petclinic findings at industrial scale: **expr-body fn -1109**, **!! -344**, **scope fn -413**, **`?.` -216**. Plus a useful retraction: petclinic's "interpolated strings +13" was mis-attributed to J2K behaviour; OkHttp shows -225 in the same column, telling us the gap reflects each gold port's stylistic choices rather than the converter's transform. Narrative: [`docs/results-okhttp.md`](docs/results-okhttp.md).

### `edge-cases` — 25 curated stress tests across 10 categories

```
discovered 25 cases across 12 hypotheses
verdicts: pass=5 partial=10 fail=10
fixups applied: 6 across 4 files (override modifier)
```

Each case is `source.java` + `expected.kt` + `notes.md` testing a falsifiable claim about J2K behaviour. Five hypotheses got at least one Pass (i.e., refuted on at least one case): **H6** (J2K *does* convert Java records to `@JvmRecord data class`), **H10** (J2K *does* duplicate switch-fallthrough body to preserve semantics), **H9** (`@kotlin.Throws` correctly emitted with all exception types), **H11** (`@kotlin.jvm.Synchronized` correctly emitted), **H2** (correctly preserves write-only setter as a function on the negative-control case). Narrative: [`docs/edge-cases-report.md`](docs/edge-cases-report.md). Hypothesis writeup: [`docs/hypotheses.md`](docs/hypotheses.md).

## Reproducing locally

### Prerequisites
- **JDK 17** (Temurin or any compatible distribution). The Gradle build configures a JDK 17 toolchain; if 17 isn't installed, Gradle will download one.
- **git** with submodule support.
- **~2 GB free disk** for the IntelliJ Community artifacts the build downloads on first run.
- Linux / macOS / Windows all supported. On Linux CI runners we wrap `runIde` with `xvfb-run` (see `.github/workflows/j2k-eval.yml`) because the IntelliJ launcher probes X11 at startup; not needed for local interactive runs.

### Steps

```bash
# 1. Clone, then init submodules NON-recursively. (`git clone --recurse-submodules`
#    on git 2.13+ recurses into nested submodules by default — and OkHttp 3.x has
#    a nested `git://` submodule that hangs because GitHub disabled the
#    unauthenticated git:// protocol in 2022. We don't need it; non-recursive
#    init is enough.)
git clone https://github.com/bbugdigger/java2kotlin-eval.git
cd java2kotlin-eval
git submodule update --init                           # top-level only, no --recursive

# 2. Run the full pipeline against your target of choice.
./gradlew runEval -Ptarget=spring-petclinic           # ~80s warm cache
./gradlew runEval -Ptarget=okhttp                     # ~80s warm cache
./gradlew runEdgeCases -Ptarget=edge-cases            # ~30s warm cache

# 3. Read the report.
cat build/reports/spring-petclinic/report.md
```

**First-run cost.** ~10–15 min, dominated by:
- IntelliJ Platform Gradle plugin downloading IntelliJ Community 2024.1.7 (~600 MB)
- Each target's Maven configurator pulling its dependency tree into `~/.m2`

**Subsequent runs.** ~30–80 s — both downloads above are cached.

### Other invocations

```bash
# Just convert (skip eval):
./gradlew :j2k-runner:runIde -Ptarget=spring-petclinic

# Just score (assumes converted output already exists):
./gradlew :eval:runEval -Ptarget=spring-petclinic

# Edge cases without the OverrideFixup (baseline comparison):
./gradlew runEdgeCases -Ptarget=edge-cases -Pedgecases.applyFixups=false

# Run all 27 unit tests for the eval engine:
./gradlew :eval:test

# Convert an ad-hoc directory not in the registry:
./gradlew :j2k-runner:runIde \
  -Pj2k.project=path/to/some-maven-project \
  -Pj2k.input=path/to/some-maven-project/src/main/java \
  -Pj2k.output=build/converted/some-maven-project
```

## Layout

```
.
├── build.gradle.kts                      # Root: target registry + runEval/runEdgeCases orchestrators
├── settings.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml             # Pinned tooling (single source of truth)
│
├── j2k-runner/                           # IntelliJ plugin module
│   └── src/main/
│       ├── kotlin/.../J2kRunnerStarter.kt   # ApplicationStarter — drives JavaToKotlinAction.Handler
│       └── resources/META-INF/plugin.xml
│
├── eval/                                 # Eval engine module
│   ├── src/main/kotlin/.../
│   │   ├── PsiLoader.kt                  # KtFile parsing via kotlin-compiler-embeddable
│   │   ├── Declarations.kt               # walk PSI → flat list of named declarations
│   │   ├── DeclarationPairer.kt          # FQN match + signature fallback
│   │   ├── IdiomCounter.kt               # 12 idiom counters per declaration
│   │   ├── HistogramSimilarity.kt        # PSI element-type histogram cosine
│   │   ├── Compilability.kt              # K2JVMCompiler-driven compile check
│   │   ├── Scoring.kt                    # orchestrates the analysis layers
│   │   ├── ReportRenderer.kt             # real-world target Markdown + JSON
│   │   ├── EdgeCases.kt                  # data model + dataset discovery
│   │   ├── EdgeCaseRunner.kt             # per-case Pass/Partial/Fail verdict
│   │   ├── EdgeCaseReportRenderer.kt     # edge-case Markdown + JSON
│   │   ├── OverrideFixup.kt              # Phase-12 J2K bug workaround
│   │   ├── EvalMain.kt                   # CLI entry — real-world targets
│   │   └── EdgeCaseEvalMain.kt           # CLI entry — edge cases + fixup
│   └── src/test/kotlin/.../              # 27 unit tests covering each module
│
├── targets/                              # OSS targets (git submodules, pinned)
│   ├── spring-petclinic/                 # Java source — 30 files
│   ├── spring-petclinic-kotlin/          # Gold Kotlin port (Spring Boot 3.2-era)
│   ├── okhttp-java/                      # square/okhttp @ parent-3.14.2 — 103 files
│   └── okhttp-kotlin/                    # square/okhttp @ parent-4.12.0 — gold
│
├── edge-cases/                           # Curated stress-test dataset
│   ├── README.md                         # Index by category, hypothesis cross-links
│   └── <category>/<case>/                # 25 leaves across 10 categories
│       ├── source.java                   #   Java input (≤30 LOC)
│       ├── expected.kt                   #   Idiomatic Kotlin we hope J2K produces
│       └── notes.md                      #   Hypothesis ID + why this case is tricky
│
├── docs/
│   ├── results-spring-petclinic.md       # Narrative report — spring-petclinic
│   ├── results-okhttp.md                 # Narrative report — okhttp
│   ├── hypotheses.md                     # H1–H12 with Status + Evidence (post-validation)
│   ├── edge-cases-report.md              # Narrative report — edge cases + Phase-12 fixup impact
│   └── proposed-j2k-fix.md               # Phase-12 upstream patch sketch for missing-override bug
│
└── .github/workflows/j2k-eval.yml        # CI: runs runEval against the matrix on every push/PR
```

## Architecture in one diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│  ./gradlew runEval -Ptarget=<spring-petclinic | okhttp>                   │
└──────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
        ┌─────────────────────────────────────────────────┐
        │  :j2k-runner:runIde  (CONVERSION)                │
        │                                                  │
        │  1. copy targets/<target>/ → <out>/.work/project │
        │  2. boot headless IntelliJ Community 2024.1.7    │
        │  3. open work copy as a Maven project            │
        │  4. JavaToKotlinAction.Handler.convertFiles(...) │
        │  5. move resulting .kt → build/converted/<tgt>/  │
        └─────────────────────────────────────────────────┘
                                  │
                                  ▼
        ┌─────────────────────────────────────────────────┐
        │  :eval:runEval  (SCORING)                        │
        │                                                  │
        │  1. parse build/converted/<tgt>/**.kt as PSI     │
        │  2. parse targets/<gold>/**.kt as gold PSI       │
        │  3. collect named declarations on both sides     │
        │  4. pair by FQN + signature                      │
        │  5. score each pair: histogram cosine + idioms   │
        │  6. render report.md + report.json               │
        │       → build/reports/<tgt>/                     │
        └─────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│  ./gradlew runEdgeCases -Ptarget=edge-cases                               │
└──────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
        ┌─────────────────────────────────────────────────┐
        │  stageEdgeCases (batch staging)                  │
        │  edge-cases/**/source.java  →                    │
        │       build/.work/edge-cases-input/<pkg>/...     │
        └─────────────────────────────────────────────────┘
                                  │
                                  ▼
        ┌─────────────────────────────────────────────────┐
        │  :j2k-runner:runIde  (CONVERSION, batched)       │
        │  → build/converted/edge-cases/                   │
        └─────────────────────────────────────────────────┘
                                  │
                                  ▼
        ┌─────────────────────────────────────────────────┐
        │  :eval:runEdgeCases  (VERDICT)                   │
        │                                                  │
        │  1. snapshot pre-fixup output                    │
        │  2. OverrideFixup: kotlinc → identify missing-   │
        │     override functions → prepend `override `     │
        │  3. for each case: load expected.kt + converted  │
        │     .kt; pair by FQN; compute Pass/Partial/Fail  │
        │  4. roll up by hypothesis (Confirmed / Refuted / │
        │     Partial)                                     │
        │  5. render report.md + report.json               │
        │       → build/reports/edge-cases/                │
        └─────────────────────────────────────────────────┘
```

## Pinned tooling — and why these specific versions

| Component | Version | Why this version |
|---|---|---|
| IntelliJ Community | **2024.1.7** | 2025.1+ ships a K2-default Kotlin plugin whose post-processor can't find Kotlin builtin VFS in our headless application context — both `J2kConverterExtension` paths return empty results. 2024.1's K1 J2K, invoked via `JavaToKotlinAction.Handler`, converts cleanly. |
| IntelliJ Platform Gradle plugin | **2.5.0** | Compatible with both 2024.1.7 and Gradle 8.x. The newer 2.16.0 requires Gradle 9. |
| Gradle | **8.10.2** | Plugin 2.5.0 doesn't need Gradle 9. |
| Kotlin | **1.9.24** | Matches what IntelliJ 2024.1 bundles, avoids stdlib version conflict. |
| JDK toolchain | **17** | 2024.1 is the last IntelliJ that runs on JDK 17 (2025.1+ requires 21). |

Single source of truth: [`gradle/libs.versions.toml`](gradle/libs.versions.toml). Bumping any of these requires re-validating the headless invocation pattern.

## CI

Every push to `main` and every pull request triggers [`.github/workflows/j2k-eval.yml`](.github/workflows/j2k-eval.yml), which runs `runEval` against each target in a matrix (currently `spring-petclinic` and `okhttp`). The rendered Markdown report is appended to `$GITHUB_STEP_SUMMARY` so reviewers see results inline on the run page; the full report and converted output are uploaded as the `eval-<target>` artifact.

The workflow uses `actions/checkout@v4` with `submodules: true` (not `recursive`) — recursing would hit the `git://` protocol issue in OkHttp 3.x's nested submodules. See `memory/project_ci_submodule_gotcha.md` for the full story.

[Latest CI runs →](https://github.com/bbugdigger/java2kotlin-eval/actions)

## What's in `docs/`

| File | Contents | Audience |
|---|---|---|
| [`results-spring-petclinic.md`](docs/results-spring-petclinic.md) | Narrative report on the petclinic target — TL;DR, methodology, headline metrics, orphan analysis, idiom delta interpretation, "why 99% similarity is the floor not the ceiling" honesty section, caveats | First read for "what does this thing find?" |
| [`results-okhttp.md`](docs/results-okhttp.md) | Same for okhttp + cross-target comparison with petclinic — including the interpolated-strings retraction | Read after petclinic for the "does it generalise?" question |
| [`hypotheses.md`](docs/hypotheses.md) | 12 falsifiable hypotheses about J2K behaviour, each with Claim / Rationale / Linked edge cases / Status / Evidence | Research-style summary of "what we predicted and what J2K actually does" |
| [`edge-cases-report.md`](docs/edge-cases-report.md) | Per-case verdicts, per-hypothesis status rollup, "notable wins" with code samples, confirmed-failures by severity, Phase-12 fixup impact | Companion to `hypotheses.md` |
| [`proposed-j2k-fix.md`](docs/proposed-j2k-fix.md) | Upstream J2K patch sketch — what we'd propose to fix the missing-`override` bug at the source | Demonstrates J2K-internals understanding |

## Adding a new OSS target

1. `git submodule add https://github.com/<org>/<repo>.git targets/<repo>`
2. Add an entry to `evalTargets` in `build.gradle.kts`:
   ```kotlin
   "your-target" to mapOf(
       "projectDir" to "targets/your-target",
       "inputDir"   to "targets/your-target/src/main/java",
       "outputDir"  to "build/converted/your-target",
       "goldDir"    to "<path to gold Kotlin port>",
       "reportDir"  to "build/reports/your-target",
       "openType"   to "Maven",  // or "Gradle"
   ),
   ```
3. Add `your-target` to the `matrix.target` list in `.github/workflows/j2k-eval.yml`.
4. `./gradlew runEval -Ptarget=your-target` to verify locally.

The OkHttp target is a worked example of this — see the `"okhttp" to mapOf(...)` entry in `build.gradle.kts` and the `targets/okhttp-java` + `targets/okhttp-kotlin` submodules. Note the same upstream repo (`square/okhttp`) is checked out at two different tags — one for the Java baseline (`parent-3.14.2`, last all-Java release) and one for the Kotlin gold (`parent-4.12.0`, mature Kotlin codebase).
