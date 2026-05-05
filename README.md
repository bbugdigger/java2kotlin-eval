# java2kotlin-eval

[![j2k-eval](https://github.com/bbugdigger/java2kotlin-eval/actions/workflows/j2k-eval.yml/badge.svg)](https://github.com/bbugdigger/java2kotlin-eval/actions/workflows/j2k-eval.yml)

A reproducible CI pipeline that drives JetBrains' static **J2K** (Java → Kotlin) converter against real-world OSS Java projects and scores the output against hand-written "gold" Kotlin ports using PSI structural analysis.

## What this is

Two cooperating Gradle modules wired into a single command:

1. **`j2k-runner`** — a tiny IntelliJ plugin that registers an `ApplicationStarter`. Boots a headless IntelliJ Community 2024.1.7, opens the target as a Maven project, and invokes `JavaToKotlinAction.Handler.convertFiles(...)` — the IDE's own conversion entry point — against every `.java` under the source root.
2. **`eval`** — a Kotlin executable that parses both the converted output and a "gold" Kotlin port using `kotlin-compiler-embeddable`, pairs declarations by FQN (with overload-signature fallback), and emits a four-section Markdown report + machine-readable JSON. Scoring layers: PSI element-type histogram cosine similarity (one principled per-pair number) + counts of 12 specific Kotlin idioms (val/var, expression-body fns, scope fns, `?.`, `!!`, data class, primary-ctor properties, …).

A single command runs the full pipeline:

```bash
./gradlew runEval -Ptarget=spring-petclinic
```

The output:
- `build/converted/spring-petclinic/**.kt` — J2K's actual conversion output (30 files for petclinic)
- `build/reports/spring-petclinic/report.md` — human-readable scorecard
- `build/reports/spring-petclinic/report.json` — machine-readable scorecard

## Headline result (spring-petclinic)

```
attempted=30 converted=30 failed=0
matched=73 / 96 gold declarations (76% pairing rate)
mean PSI similarity (paired) = 0.99
```

J2K converts every file cleanly and tracks the gold port's overall structure, but **systematically diverges** along measurable idiom axes — never produces expression-body functions (-28 vs. gold), never folds fields into primary-constructor properties (-9), avoids `!!` entirely (-4), but uses string interpolation *more* often than gold (+13).

The narrative report — including caveats and what the headline number does and doesn't mean — is at **[`docs/results-spring-petclinic.md`](docs/results-spring-petclinic.md)**.

## Reproducing locally

### Prerequisites
- **JDK 17** (Temurin or any compatible distribution). The Gradle build configures a JDK 17 toolchain; if 17 isn't installed, Gradle will download one.
- **git** with submodule support.
- **~2 GB free disk** for the IntelliJ Community artifacts the build downloads on first run.
- Linux / macOS / Windows all supported. On Linux CI runners we wrap `runIde` with `xvfb-run` (see `.github/workflows/j2k-eval.yml`) because the IntelliJ launcher probes X11 at startup; not needed for local interactive runs.

### Steps

```bash
# 1. Clone with submodules (the Java sources + the gold Kotlin port live as submodules
#    under targets/, pinned to specific commits for reproducibility).
git clone --recurse-submodules https://github.com/bbugdigger/java2kotlin-eval.git
cd java2kotlin-eval

# 2. Run the full pipeline against spring-petclinic.
./gradlew runEval -Ptarget=spring-petclinic

# 3. Read the report.
cat build/reports/spring-petclinic/report.md
```

**First-run cost.** ~10–15 min, dominated by:
- IntelliJ Platform Gradle plugin downloading IntelliJ Community 2024.1.7 (~600 MB)
- spring-petclinic's Maven configurator pulling Spring Boot 3.x dependencies into `~/.m2`

**Subsequent runs.** ~30–60 s — both downloads above are cached.

### Other invocations

```bash
# Just convert (skip eval):
./gradlew :j2k-runner:runIde -Ptarget=spring-petclinic

# Just score (assumes converted output already exists):
./gradlew :eval:runEval -Ptarget=spring-petclinic

# Run unit tests for the eval engine:
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
├── build.gradle.kts                      # Root: target registry + runEval orchestrator
├── settings.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml             # Pinned tooling (single source of truth)
│
├── j2k-runner/                           # IntelliJ plugin module
│   └── src/main/
│       ├── kotlin/.../J2kRunnerStarter.kt
│       └── resources/META-INF/plugin.xml
│
├── eval/                                 # Eval engine module
│   ├── src/main/kotlin/.../
│   │   ├── PsiLoader.kt                  # KtFile parsing via kotlin-compiler-embeddable
│   │   ├── Declarations.kt               # walk PSI → flat list of named declarations
│   │   ├── DeclarationPairer.kt          # FQN match + signature fallback
│   │   ├── IdiomCounter.kt               # 12 idiom counters per declaration
│   │   ├── HistogramSimilarity.kt        # PSI element-type histogram cosine
│   │   ├── Scoring.kt                    # orchestrates the analysis layers
│   │   ├── ReportRenderer.kt             # Markdown + hand-rolled JSON
│   │   └── EvalMain.kt                   # CLI entry
│   └── src/test/kotlin/.../              # 14 unit tests covering each module
│
├── targets/                              # OSS targets (git submodules, pinned)
│   ├── spring-petclinic/                 # Java source — input
│   └── spring-petclinic-kotlin/          # Gold Kotlin port — comparison reference
│
├── docs/
│   └── results-spring-petclinic.md       # Canonical narrative report
│
└── .github/workflows/j2k-eval.yml        # CI: runs runEval against every target on every push/PR
```

## Architecture in one diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│  ./gradlew runEval -Ptarget=spring-petclinic                          │
└──────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
        ┌─────────────────────────────────────────────────┐
        │  :j2k-runner:runIde  (CONVERSION)                │
        │                                                  │
        │  1. copy targets/spring-petclinic                │
        │       → build/converted/spring-petclinic/.work/  │
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
        │  2. parse targets/spring-petclinic-kotlin/**.kt  │
        │       as gold PSI                                │
        │  3. collect named declarations on both sides     │
        │  4. pair by FQN + signature                      │
        │  5. score each pair: histogram cosine + idioms   │
        │  6. render report.md + report.json               │
        │       → build/reports/<tgt>/                     │
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

Every push to `main` and every pull request triggers [`.github/workflows/j2k-eval.yml`](.github/workflows/j2k-eval.yml), which runs `runEval` against each target in a matrix (currently just `spring-petclinic`). The rendered Markdown report is appended to `$GITHUB_STEP_SUMMARY` so reviewers see results inline on the run page; the full report and converted output are uploaded as the `eval-<target>` artifact.

[Latest CI runs →](https://github.com/bbugdigger/java2kotlin-eval/actions)

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

## License

Source code: MIT (see [`LICENSE`](LICENSE) — to be added).
The pinned submodules under `targets/` are the property of their respective upstream maintainers.
