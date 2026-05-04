import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(libs.versions.intellijPlatform.get())
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.jetbrains.kotlin")
    }
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "243.*"
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}

// Customize runIde to invoke our ApplicationStarter ("j2k").
//
// Two ways to pass conversion config:
//   1. Per-target shortcut (recommended):
//        ./gradlew :j2k-runner:runIde -Ptarget=spring-petclinic
//      Args are looked up from rootProject.extra["evalTargets"][target].
//   2. Explicit per-property (for one-off runs / new targets):
//        ./gradlew :j2k-runner:runIde -Pj2k.project=<dir> -Pj2k.input=<dir>
//                                     -Pj2k.output=<dir> [-Pj2k.openType=Maven|Gradle]
//
// The IDE process inherits a working directory inside the Gradle artifact cache, so
// relative paths from the user are resolved against the root project here before being
// forwarded as args.
@Suppress("UNCHECKED_CAST")
private val evalTargets: Map<String, Map<String, String>> =
    (rootProject.extra["evalTargets"] as Map<String, Map<String, String>>?) ?: emptyMap()

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    val rootDir = rootProject.projectDir
    fun toAbs(p: String): String {
        val f = File(p)
        return if (f.isAbsolute) f.absolutePath else File(rootDir, p).absolutePath
    }

    val targetName = providers.gradleProperty("target")
    val explicitProject = providers.gradleProperty("j2k.project")
    val explicitInput = providers.gradleProperty("j2k.input")
    val explicitOutput = providers.gradleProperty("j2k.output")
    val explicitOpenType = providers.gradleProperty("j2k.openType")
    val targets = evalTargets

    argumentProviders.add(CommandLineArgumentProvider {
        val tgt = targetName.orNull?.let { targets[it] }
        val project = tgt?.get("projectDir") ?: explicitProject.orNull
        val input = tgt?.get("inputDir") ?: explicitInput.orNull
        val output = tgt?.get("outputDir") ?: explicitOutput.orNull
        listOfNotNull(
            "j2k",
            project?.let { "--project=${toAbs(it)}" },
            input?.let { "--input=${toAbs(it)}" },
            output?.let { "--output=${toAbs(it)}" }
        )
    })

    jvmArgumentProviders.add(CommandLineArgumentProvider {
        val tgt = targetName.orNull?.let { targets[it] }
        val openType = tgt?.get("openType") ?: explicitOpenType.orNull ?: "Maven"
        listOf(
            "-Djava.awt.headless=true",
            "-Didea.headless.enable.statistics=false",
            "-Didea.platform.prefix=Idea",
            "-Dproject.open.type=$openType"
        )
    })
}

// Friendlier alias.
tasks.register("runJ2k") {
    group = "j2k"
    description = "Runs the headless J2K converter. " +
        "Use -Ptarget=<name> (preferred) or -Pj2k.project/-Pj2k.input/-Pj2k.output."
    dependsOn("runIde")
}
