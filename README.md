# java2kotlin-eval

An evaluation pipeline for JetBrains' static **J2K** (Java→Kotlin) converter.

This repository runs J2K headlessly against real-world OSS Java projects in CI, then scores the conversion against a hand-curated "gold" Kotlin port using PSI structural analysis.

> **Status:** Phase 0 scaffolding only. See `docs/` (added in later phases) and the in-repo plan for the full roadmap.

## Modules

| Module | Purpose |
|---|---|
| `j2k-runner` | Tiny IntelliJ plugin registering an `ApplicationStarter` that drives J2K headlessly. (Phase 1) |
| `eval` | Kotlin executable: PSI-based declaration pairing + idiom counting + histogram similarity → Markdown/JSON scorecard. (Phase 3) |

## Pinned tooling

- JDK 17 (toolchain)
- Kotlin 2.2.21
- Gradle 8.13
- IntelliJ Platform 2025.1
- IntelliJ Platform Gradle plugin v2 (2.0.1)

## Quick smoke check (Phase 0)

```bash
./gradlew tasks --all
```

Both `j2k-runner` and `eval` should appear under "Project tasks". Full reproduction instructions land in Phase 7.
