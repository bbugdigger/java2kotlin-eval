plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "io.github.bbugdigger"
    version = "0.1.0-SNAPSHOT"
}

/**
 * Eval target registry. Single source of truth for the OSS projects this pipeline
 * evaluates against. Both `:j2k-runner` and `:eval` read from `rootProject.extra["evalTargets"]`
 * at task execution time. Adding a new target is a single edit.
 *
 * Stored as a Map<String, Map<String, String>> rather than a typed data class because
 * subproject build scripts can't reference types defined in the root build script
 * (they live in different classloaders).
 *
 * Per-target keys (all paths are relative to the repo root):
 *  - `projectDir`: IntelliJ project root (must contain pom.xml or build.gradle).
 *  - `inputDir`:   .java source root J2K walks.
 *  - `outputDir`:  where converted .kt files land.
 *  - `goldDir`:    human-written Kotlin port to compare against.
 *  - `reportDir`:  where the eval module writes report.md / report.json.
 *  - `openType`:   IntelliJ project configurator ("Maven" or "Gradle").
 */
val evalTargets: Map<String, Map<String, String>> = mapOf(
    "spring-petclinic" to mapOf(
        "projectDir" to "targets/spring-petclinic",
        "inputDir" to "targets/spring-petclinic/src/main/java",
        "outputDir" to "build/converted/spring-petclinic",
        "goldDir" to "targets/spring-petclinic-kotlin/src/main/kotlin",
        "reportDir" to "build/reports/spring-petclinic",
        "openType" to "Maven",
    ),
)

extra["evalTargets"] = evalTargets

/**
 * Top-level convenience task. Pipes a single target through the runner and the eval
 * engine, writing report.md + report.json under `<target>.reportDir`.
 *
 * Usage: `./gradlew runEval -Ptarget=<name>`
 *
 * Per-subproject tasks `:j2k-runner:runIde` and `:eval:runEval` also accept
 * `-Ptarget=<name>` directly if you want to skip one half of the pipeline.
 */
tasks.register("runEval") {
    group = "j2k-eval"
    description = "Convert .java to .kt with J2K, then evaluate vs gold. Pass -Ptarget=<name>."

    val target = providers.gradleProperty("target").orNull
    val isValidTarget = target != null && evalTargets.containsKey(target)

    if (isValidTarget) {
        dependsOn(":j2k-runner:runIde")
        dependsOn(":eval:runEval")
    } else {
        // Don't wire the heavy dependencies if -Ptarget is missing/invalid; fail fast
        // with a helpful message at execution instead of letting both subtasks blow up
        // with their own confusing errors.
        doFirst {
            error(
                "Pass -Ptarget=<name>. " +
                    if (target == null) "Known targets: ${evalTargets.keys.joinToString()}"
                    else "Unknown target '$target'. Known: ${evalTargets.keys.joinToString()}"
            )
        }
    }
}

// Ensure conversion runs before evaluation regardless of Gradle's task-graph ordering.
project(":eval").afterEvaluate {
    tasks.matching { it.name == "runEval" }.configureEach {
        mustRunAfter(":j2k-runner:runIde")
    }
}
