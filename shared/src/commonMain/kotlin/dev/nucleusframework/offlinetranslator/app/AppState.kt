package dev.nucleusframework.offlinetranslator.app

import androidx.compose.runtime.Immutable
import dev.nucleusframework.offlinetranslator.domain.AppData
import dev.nucleusframework.offlinetranslator.domain.DownloadState
import dev.nucleusframework.offlinetranslator.domain.HistoryFilter
import dev.nucleusframework.offlinetranslator.domain.LlmModel
import dev.nucleusframework.offlinetranslator.domain.SkaiNetFamily
import dev.nucleusframework.offlinetranslator.domain.TranslationEngine
import dev.nucleusframework.offlinetranslator.domain.VoiceDownloadState
import dev.nucleusframework.offlinetranslator.translation.ProofreadState
import dev.nucleusframework.offlinetranslator.translation.TranslationState

enum class InstallStep { Welcome, Download, Voices }

@Immutable
sealed interface AppDialog {
    data object Hidden : AppDialog
    data class Confirm(val action: ConfirmAction) : AppDialog
    data class InstallVoice(val lang: String) : AppDialog
}

@Immutable
sealed interface ConfirmAction {
    data object PurgeHistory : ConfirmAction
    data class DeleteModel(val id: LlmModel) : ConfirmAction
    data class DeleteSkaiNetModel(val family: SkaiNetFamily, val id: LlmModel) : ConfirmAction
    data class DeleteVoice(val lang: String) : ConfirmAction
    data object ResetApp : ConfirmAction
}

/** Which model catalog a download tracks — each target can download concurrently and independently. */
@Immutable
sealed interface DownloadTarget {
    data object Gemma : DownloadTarget
    data class SkaiNet(val family: SkaiNetFamily) : DownloadTarget
}

@Immutable
data class AppState(
    val data: AppData = AppData(),
    val translation: TranslationState = TranslationState.Empty,
    val proofread: ProofreadState = ProofreadState(),
    val download: DownloadState = DownloadState(),
    val skainetDownloads: Map<SkaiNetFamily, DownloadState> = SkaiNetFamily.entries.associateWith { DownloadState() },
    val voicePicks: Set<String> = emptySet(),
    val voiceDownload: VoiceDownloadState = VoiceDownloadState(),
    val historyQuery: String = "",
    val historyFilter: HistoryFilter = HistoryFilter.All,
    val dialog: AppDialog = AppDialog.Hidden,
    val message: AppMessage? = null,
    val hostRamBytes: Long = 0L,
) {
    val offline: Boolean get() = data.settings.airplane
    val installed: Boolean get() = data.installed
    val installSteps: Int get() = if (translation.ttsReady) 3 else 2

    /** Whether the model the currently active engine (and, for SkaiNet, family) would actually
     * run with is installed — not just the LiteRT Gemma model, which [data.model] always tracks
     * regardless of which engine is selected. */
    val activeModelInstalled: Boolean
        get() = when (data.settings.engine) {
            TranslationEngine.SkaiNet -> {
                val family = data.settings.skainetFamily
                val info = data.skainetModels.getValue(family)
                info.installed && info.id == data.settings.skainetSelection.getValue(family)
            }
            TranslationEngine.LiteRt -> data.model.installed
        }
}

fun AppState.installStep(): InstallStep = parseInstallStep(data.installStep)

fun parseInstallStep(raw: String): InstallStep = when (raw) {
    "Languages" -> InstallStep.Download
    else -> runCatching { InstallStep.valueOf(raw) }.getOrDefault(InstallStep.Welcome)
}
