import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    // Root gradle.properties sets `kotlin.stdlib.default.dependency=false` so j2k-runner
    // doesn't conflict with the IntelliJ Platform's bundled stdlib. The eval module is a
    // regular Kotlin app with no IntelliJ classpath, so re-add stdlib explicitly here.
    implementation(kotlin("stdlib"))
    implementation(libs.kotlin.compiler.embeddable)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "io.github.bbugdigger.j2keval.eval.EvalMainKt"
}

tasks.test {
    useJUnitPlatform()
}

// Two ways to invoke runEval:
//   1. ./gradlew :eval:runEval -Ptarget=<name>
//      Args resolve from rootProject.extra["evalTargets"][target].
//   2. ./gradlew :eval:runEval -Peval.target=<name> -Peval.converted=<dir>
//                              -Peval.gold=<dir> -Peval.out=<dir>
//      For ad-hoc / new targets not yet in the registry.
@Suppress("UNCHECKED_CAST")
private val evalTargets: Map<String, Map<String, String>> =
    (rootProject.extra["evalTargets"] as Map<String, Map<String, String>>?) ?: emptyMap()

tasks.register<JavaExec>("runEval") {
    group = "j2k-eval"
    description = "Run the eval engine. Use -Ptarget=<name> (preferred) or " +
        "-Peval.target/-Peval.converted/-Peval.gold/-Peval.out."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.bbugdigger.j2keval.eval.EvalMainKt")

    val rootDir = rootProject.projectDir
    fun toAbs(p: String): String {
        val f = File(p)
        return if (f.isAbsolute) f.absolutePath else File(rootDir, p).absolutePath
    }

    val targetName = providers.gradleProperty("target")
    val explicitTarget = providers.gradleProperty("eval.target")
    val explicitConverted = providers.gradleProperty("eval.converted")
    val explicitGold = providers.gradleProperty("eval.gold")
    val explicitOut = providers.gradleProperty("eval.out")
    val targets = evalTargets

    argumentProviders.add(CommandLineArgumentProvider {
        val tgt = targetName.orNull?.let { targets[it] }
        val name = targetName.orNull ?: explicitTarget.orNull
        val converted = tgt?.get("outputDir") ?: explicitConverted.orNull
        val gold = tgt?.get("goldDir") ?: explicitGold.orNull
        val out = tgt?.get("reportDir") ?: explicitOut.orNull
        listOfNotNull(
            name?.let { "--target=$it" },
            converted?.let { "--converted=${toAbs(it)}" },
            gold?.let { "--gold=${toAbs(it)}" },
            out?.let { "--out=${toAbs(it)}" }
        )
    })
}

// Edge-case verdict pass. Reads the dataset, looks up each case's converted .kt under
// the converted root, runs the per-case verdict logic.
//
// Usage: typically driven through the root `runEdgeCases` orchestrator, which takes
// care of staging + conversion first. Direct invocation:
//   ./gradlew :eval:runEdgeCases -Ptarget=edge-cases
//
// Falls back to -Pedgecases.{dataset,converted,out} for ad-hoc runs.
tasks.register<JavaExec>("runEdgeCases") {
    group = "j2k-eval"
    description = "Run the per-case verdict engine over the edge-case dataset. " +
        "Use -Ptarget=edge-cases (preferred)."

    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.bbugdigger.j2keval.eval.EdgeCaseEvalMainKt")

    val rootDir = rootProject.projectDir
    fun toAbs(p: String): String {
        val f = File(p)
        return if (f.isAbsolute) f.absolutePath else File(rootDir, p).absolutePath
    }

    val targetName = providers.gradleProperty("target")
    val explicitDataset = providers.gradleProperty("edgecases.dataset")
    val explicitConverted = providers.gradleProperty("edgecases.converted")
    val explicitOut = providers.gradleProperty("edgecases.out")
    val targets = evalTargets

    argumentProviders.add(CommandLineArgumentProvider {
        val tgt = targetName.orNull?.let { targets[it] }
        // For the edge-cases preset: dataset is goldDir ("edge-cases"), converted is outputDir,
        // out is reportDir.
        val dataset = tgt?.get("goldDir") ?: explicitDataset.orNull
        val converted = tgt?.get("outputDir") ?: explicitConverted.orNull
        val out = tgt?.get("reportDir") ?: explicitOut.orNull
        listOfNotNull(
            dataset?.let { "--dataset=${toAbs(it)}" },
            converted?.let { "--converted=${toAbs(it)}" },
            out?.let { "--out=${toAbs(it)}" }
        )
    })
}
