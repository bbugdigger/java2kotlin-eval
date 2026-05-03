plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
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
