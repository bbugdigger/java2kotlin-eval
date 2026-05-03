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
        bundledPlugin("org.jetbrains.kotlin")
    }
}

kotlin {
    jvmToolchain(21) // IntelliJ Platform 2025.1+ requires JDK 21
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }

    buildSearchableOptions = false
    instrumentCode = false
}

// Customize runIde to invoke our ApplicationStarter ("j2k") with args from -P properties.
// Usage: ./gradlew :j2k-runner:runIde -Pj2k.project=<dir> -Pj2k.input=<dir> -Pj2k.output=<dir>
//
// The IDE process inherits a working directory inside the Gradle artifact cache,
// so relative paths from the user are resolved against the root project here before
// being forwarded as args.
tasks.named<org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask>("runIde") {
    val rootDir = rootProject.projectDir
    fun toAbs(p: String): String {
        val f = File(p)
        return if (f.isAbsolute) f.absolutePath else File(rootDir, p).absolutePath
    }

    val projectPath = providers.gradleProperty("j2k.project").map(::toAbs)
    val inputPath = providers.gradleProperty("j2k.input").map(::toAbs)
    val outputPath = providers.gradleProperty("j2k.output").map(::toAbs)

    argumentProviders.add(CommandLineArgumentProvider {
        listOfNotNull(
            "j2k",
            projectPath.orNull?.let { "--project=$it" },
            inputPath.orNull?.let { "--input=$it" },
            outputPath.orNull?.let { "--output=$it" }
        )
    })

    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Djava.awt.headless=true",
            "-Didea.suppress.statistics.report=true",
            // Force K1 mode for J2K. K2 J2K's post-processor demands a fully-configured
            // K2 module context (Kotlin facet, SDK, etc.) which we don't construct in
            // headless. K1_NEW is more permissive and is what Meta's headless J2K used.
            // We can re-enable K2 once we either (a) build proper module config or
            // (b) call J2kConverterExtension.setUpAndConvert which handles facet setup.
            "-Didea.kotlin.plugin.use.k2=false"
        )
    })
}

// Friendlier alias.
tasks.register("runJ2k") {
    group = "j2k"
    description = "Runs the headless J2K converter via the j2k-runner IntelliJ plugin. " +
        "Use -Pj2k.project=<dir> -Pj2k.input=<dir> -Pj2k.output=<dir>."
    dependsOn("runIde")
}
