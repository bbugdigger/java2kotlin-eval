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
            "-Didea.headless.enable.statistics=false",
            "-Didea.platform.prefix=Idea"
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
