package dev.nucleusframework.offlinetranslator.data

import dev.nucleusframework.offlinetranslator.domain.AUTO_LANG
import dev.nucleusframework.offlinetranslator.domain.AppData
import dev.nucleusframework.offlinetranslator.domain.LangNameStyle
import dev.nucleusframework.offlinetranslator.domain.LlmBackend
import dev.nucleusframework.offlinetranslator.domain.LlmKeepAlive
import dev.nucleusframework.offlinetranslator.domain.LlmModel
import dev.nucleusframework.offlinetranslator.domain.ModelInfo
import dev.nucleusframework.offlinetranslator.domain.SkaiNetFamily
import dev.nucleusframework.offlinetranslator.domain.ThemeMode
import dev.nucleusframework.offlinetranslator.domain.TranslationEngine
import dev.nucleusframework.offlinetranslator.domain.UiLanguage
import dev.nucleusframework.offlinetranslator.domain.UserSettings
import dev.nucleusframework.offlinetranslator.domain.defaultSkainetModels
import dev.nucleusframework.offlinetranslator.engine.GemmaModels
import dev.nucleusframework.offlinetranslator.engine.SkaiNetModels
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

private const val VERSION = "V1"

@OptIn(ExperimentalEncodingApi::class)
internal fun encodeSnapshot(data: AppData): String = buildString {
    fun b64(s: String) = Base64.Default.encode(s.encodeToByteArray())
    appendLine(VERSION)
    appendLine("installed=${data.installed}")
    appendLine("installStep=${data.installStep}")
    val s = data.settings
    appendLine("ui=${s.uiLanguage.name}")
    appendLine("uiAuto=${s.uiLanguageAuto}")
    appendLine("theme=${s.theme.name}")
    appendLine("airplane=${s.airplane}")
    appendLine("keepHistory=${s.keepHistory}")
    appendLine("autoPurge=${s.autoPurge}")
    appendLine("purgeDays=${s.purgeAfterDays}")
    appendLine("launchAtLogin=${s.launchAtLogin}")
    appendLine("threads=${s.threads}")
    appendLine("shortcut=${b64(s.shortcut)}")
    appendLine("modelDir=${b64(s.modelDir)}")
    appendLine("selectedModel=${s.selectedModel.name}")
    appendLine("skainetFamily=${s.skainetFamily.id}")
    appendLine("backend=${s.backend.name}")
    appendLine("engine=${s.engine.name}")
    appendLine("keepAlive=${s.keepAlive.name}")
    appendLine("mtp=${s.mtp}")
    appendLine("langNames=${s.langNames.name}")
    appendLine("selectedVoices=${s.selectedVoices.entries.joinToString(",") { "${it.key}=${it.value}" }}")
    val m = data.model
    appendLine("model.id=${m.id.name}")
    appendLine("model.installed=${m.installed}")
    appendLine("model.at=${m.installedAt ?: 0}")
    appendLine("model.sha=${m.sha256}")
    appendLine("model.path=${b64(m.path)}")
    appendLine("model.checked=${m.lastChecked ?: 0}")
    for (family in SkaiNetFamily.entries) {
        val tier = s.skainetSelection.getValue(family)
        val sm = data.skainetModels.getValue(family)
        appendLine("skainetSelection.${family.id}=${tier.name}")
        appendLine("skainetModel.${family.id}.id=${sm.id.name}")
        appendLine("skainetModel.${family.id}.installed=${sm.installed}")
        appendLine("skainetModel.${family.id}.at=${sm.installedAt ?: 0}")
        appendLine("skainetModel.${family.id}.sha=${sm.sha256}")
        appendLine("skainetModel.${family.id}.path=${b64(sm.path)}")
        appendLine("skainetModel.${family.id}.checked=${sm.lastChecked ?: 0}")
    }
    appendLine("sourceLang=${data.lastSourceLang}")
    appendLine("targetLang=${data.lastTargetLang}")
}

@OptIn(ExperimentalEncodingApi::class)
internal fun decodeSnapshot(text: String): AppData {
    fun unb64(s: String) = try {
        Base64.Default.decode(s).decodeToString()
    } catch (_: Exception) {
        s
    }
    var installed = false
    var installStep = "Welcome"
    var ui = UiLanguage.Fr
    // Snapshots written before this key existed hold an explicit pick — don't silently switch them
    // over to the OS language on upgrade.
    var uiAuto = false
    var theme = ThemeMode.System
    var airplane = true
    var keepHistory = true
    var autoPurge = false
    var purgeDays = 90
    var launchAtLogin = true
    var threads = 8
    var shortcut = "⌘⌃ T"
    var modelDir = ""
    var selectedModel = LlmModel.Fast
    var skainetFamily = SkaiNetFamily.LLAMA
    var skainetSelection = SkaiNetFamily.entries.associateWith { LlmModel.Fast }.toMutableMap()
    var backend = LlmBackend.Auto
    var engine = TranslationEngine.LiteRt
    var keepAlive = LlmKeepAlive.OnDemand
    var mtp = false
    var langNames = LangNameStyle.System
    var selectedVoices = emptyMap<String, String>()
    var modelId = LlmModel.Fast
    var modelInstalled = false
    var modelAt = 0L
    var modelSha = ""
    var modelChecked = 0L
    data class SkaiNetModelFields(
        var id: LlmModel = LlmModel.Fast,
        var installed: Boolean = false,
        var at: Long = 0L,
        var sha: String = "",
        var checked: Long = 0L,
    )
    val skainetModelFields = SkaiNetFamily.entries.associateWith { SkaiNetModelFields() }
    var lastSourceLang = AUTO_LANG
    var lastTargetLang = "en"

    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line == VERSION) return@forEach
        val skainetSelectionMatch = SkaiNetFamily.entries.firstOrNull { line.startsWith("skainetSelection.${it.id}=") }
        val skainetModelFamily = SkaiNetFamily.entries.firstOrNull { line.startsWith("skainetModel.${it.id}.") }
        when {
            line.startsWith("installed=") -> installed = line.substringAfter("=").toBoolean()

            line.startsWith("installStep=") -> installStep = line.substringAfter("=")

            line.startsWith("ui=") -> ui = runCatching { UiLanguage.valueOf(line.substringAfter("=")) }.getOrDefault(UiLanguage.Fr)

            line.startsWith("uiAuto=") -> uiAuto = line.substringAfter("=").toBoolean()

            line.startsWith("theme=") -> theme = runCatching { ThemeMode.valueOf(line.substringAfter("=")) }.getOrDefault(ThemeMode.System)

            line.startsWith("airplane=") -> airplane = line.substringAfter("=").toBoolean()

            line.startsWith("keepHistory=") -> keepHistory = line.substringAfter("=").toBoolean()

            line.startsWith("autoPurge=") -> autoPurge = line.substringAfter("=").toBoolean()

            line.startsWith("purgeDays=") -> purgeDays = line.substringAfter("=").toIntOrNull() ?: 90

            line.startsWith("launchAtLogin=") -> launchAtLogin = line.substringAfter("=").toBoolean()

            line.startsWith("threads=") -> threads = line.substringAfter("=").toIntOrNull() ?: 8

            line.startsWith("shortcut=") -> shortcut = unb64(line.substringAfter("="))

            line.startsWith("modelDir=") -> modelDir = unb64(line.substringAfter("="))

            line.startsWith("selectedModel=") ->
                selectedModel =
                    runCatching { LlmModel.valueOf(line.substringAfter("=")) }.getOrDefault(LlmModel.Fast)

            line.startsWith("skainetFamily=") -> {
                val raw2 = line.substringAfter("=")
                skainetFamily = SkaiNetFamily.entries.firstOrNull { it.id == raw2 } ?: SkaiNetFamily.LLAMA
            }

            skainetSelectionMatch != null -> {
                val tier = runCatching { LlmModel.valueOf(line.substringAfter("=")) }.getOrDefault(LlmModel.Fast)
                skainetSelection[skainetSelectionMatch] = tier
            }

            line.startsWith("backend=") ->
                backend =
                    runCatching { LlmBackend.valueOf(line.substringAfter("=")) }.getOrDefault(LlmBackend.Auto)

            line.startsWith("engine=") ->
                engine =
                    runCatching { TranslationEngine.valueOf(line.substringAfter("=")) }.getOrDefault(TranslationEngine.LiteRt)

            line.startsWith("keepAlive=") ->
                keepAlive =
                    runCatching { LlmKeepAlive.valueOf(line.substringAfter("=")) }.getOrDefault(LlmKeepAlive.OnDemand)

            line.startsWith("mtp=") -> mtp = line.substringAfter("=").toBoolean()

            line.startsWith("langNames=") ->
                langNames =
                    runCatching { LangNameStyle.valueOf(line.substringAfter("=")) }.getOrDefault(LangNameStyle.System)

            line.startsWith("selectedVoices=") -> selectedVoices = line.substringAfter("=").split(",")
                .mapNotNull { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0] to parts[1] else null
                }
                .toMap()

            line.startsWith("model.id=") -> modelId = runCatching { LlmModel.valueOf(line.substringAfter("=")) }.getOrDefault(LlmModel.Fast)

            line.startsWith("model.installed=") -> modelInstalled = line.substringAfter("=").toBoolean()

            line.startsWith("model.at=") -> modelAt = line.substringAfter("=").toLongOrNull() ?: 0L

            line.startsWith("model.sha=") -> modelSha = line.substringAfter("=")

            line.startsWith("model.path=") -> Unit

            line.startsWith("model.checked=") -> modelChecked = line.substringAfter("=").toLongOrNull() ?: 0L

            skainetModelFamily != null -> {
                val fields = skainetModelFields.getValue(skainetModelFamily)
                val prefix = "skainetModel.${skainetModelFamily.id}."
                when {
                    line.startsWith("${prefix}id=") ->
                        fields.id = runCatching { LlmModel.valueOf(line.substringAfter("=")) }.getOrDefault(LlmModel.Fast)
                    line.startsWith("${prefix}installed=") -> fields.installed = line.substringAfter("=").toBoolean()
                    line.startsWith("${prefix}at=") -> fields.at = line.substringAfter("=").toLongOrNull() ?: 0L
                    line.startsWith("${prefix}sha=") -> fields.sha = line.substringAfter("=")
                    line.startsWith("${prefix}path=") -> Unit
                    line.startsWith("${prefix}checked=") -> fields.checked = line.substringAfter("=").toLongOrNull() ?: 0L
                }
            }

            line.startsWith("sourceLang=") -> lastSourceLang = line.substringAfter("=").ifBlank { AUTO_LANG }

            line.startsWith("targetLang=") -> lastTargetLang = line.substringAfter("=").ifBlank { "en" }
        }
    }

    return AppData(
        installed = installed,
        installStep = installStep,
        settings = UserSettings(
            uiLanguage = ui,
            uiLanguageAuto = uiAuto,
            theme = theme,
            airplane = airplane,
            keepHistory = keepHistory,
            autoPurge = autoPurge,
            purgeAfterDays = purgeDays,
            launchAtLogin = launchAtLogin,
            threads = threads,
            shortcut = shortcut,
            modelDir = modelDir,
            selectedModel = selectedModel,
            skainetFamily = skainetFamily,
            skainetSelection = skainetSelection,
            backend = backend,
            engine = engine,
            keepAlive = keepAlive,
            mtp = mtp,
            langNames = langNames,
            selectedVoices = selectedVoices,
        ),
        lastSourceLang = lastSourceLang,
        lastTargetLang = lastTargetLang,
        model = GemmaModels.of(modelId).let { catalog ->
            ModelInfo(
                id = modelId,
                installed = modelInstalled,
                installedAt = modelAt.takeIf { it > 0 },
                sha256 = modelSha,
                path = if (modelInstalled) catalog.destPath() else "",
                lastChecked = modelChecked.takeIf { it > 0 },
                name = catalog.name,
                quantization = catalog.quantization,
                expectedBytes = catalog.bytes,
            )
        },
        skainetModels = defaultSkainetModels().mapValues { (family, default) ->
            val fields = skainetModelFields.getValue(family)
            val catalog = SkaiNetModels.of(family, fields.id)
            ModelInfo(
                id = fields.id,
                installed = fields.installed,
                installedAt = fields.at.takeIf { it > 0 },
                sha256 = fields.sha,
                path = if (fields.installed) catalog.destPath() else "",
                lastChecked = fields.checked.takeIf { it > 0 },
                name = if (fields.installed) catalog.name else default.name,
                version = default.version,
                quantization = if (fields.installed) catalog.quantization else default.quantization,
                expectedBytes = if (fields.installed) catalog.bytes else default.expectedBytes,
            )
        },
    )
}
