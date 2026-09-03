import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.nucleusframework.offlinetranslator.androidApp"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        targetSdk = 37

        applicationId = "dev.nucleusframework.offlinetranslator.androidApp"
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // kotlinx-coroutines-core and kotlinx-serialization-json both publish a "-metadata"
            // KMP artifact bundling Kotlin/Native klib common-metadata (.knm files, manifest,
            // linkdata index) under commonMain/** and nativeMain/** at the SAME paths in both
            // jars — cross-compile tooling data, never loaded at runtime on Android (which only
            // uses the JVM-target classfiles), so the whole tree is safe to drop from the merge.
            excludes += "commonMain/**"
            excludes += "nativeMain/**"
            excludes += "META-INF/kotlin-project-structure-metadata.json"
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activityCompose)
}
