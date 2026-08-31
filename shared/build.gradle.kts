import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.metro)
    alias(libs.plugins.sqlDelight)
    alias(libs.plugins.structured.coroutines)
    alias(libs.plugins.stability.analyzer)
    alias(libs.plugins.aboutLibraries)
}

kotlin {
    android {
        namespace = "dev.nucleusframework.offlinetranslator"
        compileSdk = 37
        minSdk = 26
        androidResources.enable = true
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }

    jvm {
        // SKaiNET's SIMD path needs jdk.incubator.vector (21+); calling its Kotlin API directly
        // (not through the Java-interop KLlamaJava facade) needs 21+ bytecode for inline functions.
        compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.compose.foundation)
            api(libs.compose.resources)
            api(libs.compose.ui.tooling.preview)
            api(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.structured.coroutines.annotations)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.client.serialization)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime)
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)
            implementation(libs.compose.nav3)
            implementation(libs.multiplatformSettings)
            implementation(libs.kotlinx.datetime)
            implementation(libs.materialKolor)
            implementation(libs.aboutlibraries.compose.m3)
            implementation(libs.sqlDelight.runtime)
            api(libs.filekit.core)
            api(libs.filekit.dialogs)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.compose.ui.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.dependencies {
            implementation(libs.ktor.client.mock)
        }

        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqlDelight.driver.android)
            implementation(libs.litertlm.android)
            implementation(libs.androidx.activityCompose)
            implementation(project.dependencies.platform(libs.skainet.bom))
            implementation(libs.skainet.lang.core)
            implementation(libs.skainet.backend.api)
            implementation(libs.skainet.backend.cpu)
            implementation(libs.skainet.backend.jni.cpu)
            implementation(libs.skainet.io.core)
            implementation(libs.skainet.io.gguf)
            implementation(project.dependencies.platform(libs.skainet.transformers.bom))
            implementation(libs.skainet.transformers.core)
            implementation(libs.skainet.transformers.runtime.kllama)
            implementation(libs.skainet.transformers.inference.llama)
            implementation(libs.skainet.transformers.runtime.kgemma)
            implementation(libs.skainet.transformers.inference.gemma)
            implementation(libs.skainet.transformers.agent)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqlDelight.driver.sqlite)
            implementation(libs.sqlite.jdbc)
            implementation(libs.nucleus.core.runtime)
            implementation(libs.nucleus.application)
            implementation(libs.nucleus.decorated.window.tao)
            implementation(libs.nucleus.system.color)
            implementation(libs.nucleus.system.info)
            implementation(libs.nucleus.native.http)
            implementation(libs.nucleus.native.http.ktor)
            implementation(libs.nucleus.updater.runtime)
            implementation(libs.litertlm.jvm)
            implementation(libs.piper.jni)
            implementation(project.dependencies.platform(libs.skainet.bom))
            implementation(libs.skainet.lang.core)
            implementation(libs.skainet.backend.native.cpu)
            implementation(libs.skainet.backend.cpu)
            implementation(libs.skainet.io.core)
            implementation(libs.skainet.io.gguf)
            implementation(project.dependencies.platform(libs.skainet.transformers.bom))
            implementation(libs.skainet.transformers.core)
            implementation(libs.skainet.transformers.runtime.kllama)
            implementation(libs.skainet.transformers.inference.llama)
            implementation(libs.skainet.transformers.runtime.kgemma)
            implementation(libs.skainet.transformers.inference.gemma)
            // Gemma4ChatModel's ChatModel-SPI surface — JVM-only, kgemma doesn't expose these transitively.
            implementation(libs.skainet.transformers.providers)
            implementation(libs.skainet.transformers.api)
            implementation(libs.skainet.transformers.agent)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("dev.nucleusframework.offlinetranslator.db")
        }
    }
}

structuredCoroutines {
    useKmpCommonProfile()
}

compose.resources {
    // Independent of rootProject.name — renaming the app must not move Res.
    packageOfResClass = "offlinetranslator.shared.generated.resources"
}

val stabilityConfig = rootProject.layout.projectDirectory.file("config/stability-config.conf")

composeCompiler {
    stabilityConfigurationFiles.add(stabilityConfig)
}

aboutLibraries {
    export {
        outputFile = file("src/commonMain/composeResources/files/aboutlibraries.json")
    }
    library {
        duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
    }
}

tasks.matching {
    it.name.startsWith("generateResourceAccessorsForCommonMain") ||
        it.name.startsWith("copyNonXmlValueResourcesForCommonMain") ||
        it.name.startsWith("prepareComposeResourcesTaskForCommonMain")
}.configureEach {
    dependsOn("exportLibraryDefinitions")
}

composeStabilityAnalyzer {
    stabilityConfigurationFiles.add(stabilityConfig)
    traceAll {
        enabled.set(true)
        threshold.set(2)
        variants.set(listOf("debug"))
    }
    stabilityValidation {
        enabled.set(true)
        outputDir.set(layout.projectDirectory.dir("stability"))
        includeTests.set(false)
        failOnStabilityChange.set(true)
    }
}
