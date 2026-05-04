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
