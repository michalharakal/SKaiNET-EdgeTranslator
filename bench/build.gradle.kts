import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Headless benchmark harness (docs/PERF-LOGBOOK.md's planned ":bench module"): drives the
// app's own Translator implementations (SkaiNetTranslator / GemmaTranslator) outside the UI
// and records model-load time, time-to-first-token, decode rate, end-to-end latency and peak
// memory on a fixed prompt set. Plain kotlin-jvm + application — deliberately NOT the nucleus
// plugin, so :bench:run owns its jvmArgs (the desktopApp afterEvaluate battle doesn't apply).
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.nucleus.system.info)
}

application {
    mainClass.set("dev.nucleusframework.offlinetranslator.bench.BenchMainKt")
    // Same flags :desktopApp:run needs: SKaiNET's SIMD kernel provider wants
    // jdk.incubator.vector, and the Panama FFM path is preview API on JDK 21.
    applicationDefaultJvmArgs = listOf("--enable-preview", "--add-modules", "jdk.incubator.vector")
}

tasks.named<JavaExec>("run") {
    // Version stamps for the results file, read from the catalog so they can't drift.
    systemProperty("bench.skainet.version", libs.versions.skainet.get())
    systemProperty("bench.skainetTransformers.version", libs.versions.skainetTransformers.get())
    systemProperty("bench.litertlm.version", libs.versions.litertlm.get())
    workingDir = rootDir
}
