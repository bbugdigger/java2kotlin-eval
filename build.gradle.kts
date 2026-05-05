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
    // Edge-cases is a "synthetic target": its inputs are staged from the edge-cases/
    // dataset by the stageEdgeCases task. The runner converts the staged tree;
    // the eval module then runs the per-case verdict logic instead of the fuzzy
    // similarity scoring used for real-world targets.
    "edge-cases" to mapOf(
        "projectDir" to "build/.work/edge-cases-input",
        "inputDir" to "build/.work/edge-cases-input",
        "outputDir" to "build/converted/edge-cases",
        "goldDir" to "edge-cases",  // unused by edge-case verdict logic; per-case expected.kt is read directly
        "reportDir" to "build/reports/edge-cases",
        "openType" to "Maven",  // doesn't matter — staged dir has no pom.xml/build.gradle
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

// ---------------------------------------------------------------------------
// Edge-case pipeline
// ---------------------------------------------------------------------------

/**
 * Stage `edge-cases/<category>/<case>/source.java` files under
 * `build/.work/edge-cases-input/<package_path>/source.java` so the j2k-runner can
 * walk a single tree where each Java file's location matches its declared package.
 *
 * The runner needs the package-matching layout because IntelliJ's PSI parser is
 * picky about it for some inspections, and it makes the converted output's relative
 * path predictable (which is what `EdgeCase.stagedRelativeKtPath()` relies on).
 */
val stageEdgeCases by tasks.registering {
    group = "j2k-eval"
    description = "Stage edge-cases/**/source.java under build/.work/edge-cases-input matching package paths."

    val datasetDir = file("edge-cases")
    val stagingDir = file("build/.work/edge-cases-input")
    inputs.dir(datasetDir).withPropertyName("dataset")
    outputs.dir(stagingDir)

    doLast {
        if (stagingDir.exists()) stagingDir.deleteRecursively()
        stagingDir.mkdirs()

        val packageRegex = Regex("""^\s*package\s+([\w.]+)\s*;""", RegexOption.MULTILINE)
        var staged = 0
        datasetDir.walkTopDown().filter { it.isFile && it.name == "source.java" }.forEach { src ->
            val text = src.readText()
            val pkg = packageRegex.find(text)?.groupValues?.get(1)
                ?: error("source.java in ${src.parent} is missing a package declaration")
            val dest = stagingDir.resolve(pkg.replace('.', '/')).resolve("source.java")
            dest.parentFile.mkdirs()
            src.copyTo(dest, overwrite = true)
            staged++
        }
        println("[stageEdgeCases] staged $staged source.java file(s) into $stagingDir")
    }
}

/**
 * Top-level edge-case pipeline. Equivalent of `runEval` but for the edge-case dataset.
 *
 * Usage:
 *   ./gradlew runEdgeCases -Ptarget=edge-cases
 *
 * (The `-Ptarget=edge-cases` is required so :j2k-runner:runIde knows which preset to
 * use; it's slightly redundant given the task name, but Gradle doesn't have a clean way
 * for one task to inject project properties into another.)
 */
tasks.register("runEdgeCases") {
    group = "j2k-eval"
    description = "Stage the edge-case dataset, run J2K, score per case. " +
        "Pass -Ptarget=edge-cases."

    val target = providers.gradleProperty("target").orNull
    val isCorrectTarget = target == "edge-cases"

    if (isCorrectTarget) {
        dependsOn(stageEdgeCases)
        dependsOn(":j2k-runner:runIde")
        dependsOn(":eval:runEdgeCases")
    } else {
        doFirst {
            error("Pass -Ptarget=edge-cases (this orchestrator drives the edge-case dataset).")
        }
    }
}

// Inter-task ordering for the edge-case path. mustRunAfter only takes effect when both
// tasks are in the task graph (i.e. when `runEdgeCases` is the requested goal).
project(":eval").afterEvaluate {
    tasks.matching { it.name == "runEval" }.configureEach {
        mustRunAfter(":j2k-runner:runIde")
    }
    tasks.matching { it.name == "runEdgeCases" }.configureEach {
        mustRunAfter(":j2k-runner:runIde")
    }
}
project(":j2k-runner").afterEvaluate {
    tasks.matching { it.name == "runIde" }.configureEach {
        mustRunAfter(stageEdgeCases)
    }
}
