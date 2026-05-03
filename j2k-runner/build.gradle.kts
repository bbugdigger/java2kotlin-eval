plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

// Phase 1 will add the IntelliJ Platform Gradle plugin and ApplicationStarter wiring.
