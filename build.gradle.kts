import dev.detekt.gradle.Detekt
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask

plugins {
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.compose.compiler).apply(false)
    alias(libs.plugins.compose.multiplatform).apply(false)
    alias(libs.plugins.android.application).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    alias(libs.plugins.kotlin.jvm).apply(false)
    alias(libs.plugins.metro).apply(false)
    alias(libs.plugins.sqlDelight).apply(false)
    alias(libs.plugins.structured.coroutines).apply(false)
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.stability.analyzer) apply false
    alias(libs.plugins.aboutLibraries) apply false
}

val detektVersion = libs.versions.detekt.get()
val ktlintVersion = libs.versions.ktlint.cli.get()
val detektComposeRules = libs.detekt.compose.rules
val ktlintComposeRules = libs.ktlint.compose.rules
val detektConfig = rootProject.file("config/detekt/detekt.yml")

subprojects {
    apply(plugin = "dev.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<dev.detekt.gradle.extensions.DetektExtension>("detekt") {
        toolVersion = detektVersion
        parallel = true
        buildUponDefaultConfig = false
        disableDefaultRuleSets = true
        config.setFrom(detektConfig)
        source.setFrom(
            "src/commonMain/kotlin",
            "src/commonTest/kotlin",
            "src/androidMain/kotlin",
            "src/jvmMain/kotlin",
            "src/jvmTest/kotlin",
            "src/main/kotlin",
            "src/test/kotlin",
        )
    }

    extensions.configure<KtlintExtension>("ktlint") {
        version.set(ktlintVersion)
        android.set(false)
        outputToConsole.set(true)
        ignoreFailures.set(false)
        reporters {
            reporter(ReporterType.PLAIN)
            reporter(ReporterType.CHECKSTYLE)
        }
        filter {
            exclude { element ->
                val path = element.file.path
                path.contains("/generated/") || path.contains("/build/")
            }
        }
    }

    dependencies {
        add("detektPlugins", detektComposeRules)
        add("ktlintRuleset", ktlintComposeRules)
    }

    // The published sk.ainet.transformers:skainet-transformers-runtime-kgemma-jvm:0.53.0 POM
    // declares a runtime dependency on `SKaiNET-transformers.llm-runtime:kgemma3n-jvm:unspecified`
    // — an un-published project() coordinate leaked by upstream's publishing (the Android variant
    // is clean). kgemma only uses it from its own CLI Main.kt, which this app never touches, so
    // drop the group everywhere (it has to be excluded on every resolving project, not just
    // :shared, because :desktopApp resolves its own runtimeClasspath through the same POM).
    // Remove once upstream ships a fixed POM.
    configurations.configureEach {
        exclude(group = "SKaiNET-transformers.llm-runtime")
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget.set("17")
        exclude("**/build/**")
        exclude("**/generated/**")
    }

    tasks.withType<BaseKtLintCheckTask>().configureEach {
        exclude { it.file.absolutePath.contains("/generated/") }
    }
}
