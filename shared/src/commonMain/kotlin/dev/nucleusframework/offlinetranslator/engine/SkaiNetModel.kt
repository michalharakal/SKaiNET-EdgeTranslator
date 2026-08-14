package dev.nucleusframework.offlinetranslator.engine

import androidx.compose.runtime.Immutable
import dev.nucleusframework.offlinetranslator.domain.LlmModel
import dev.nucleusframework.offlinetranslator.platform.Platform
import dev.nucleusframework.offlinetranslator.platform.joinPath
import dev.nucleusframework.offlinetranslator.platform.parentPath

/**
 * Llama-3.2-Instruct GGUF, Q4_K_M — not Gemma. SKaiNET-transformers' only Gemma facade
 * (`Gemma4ChatModel.fromSafeTensors`) is FP32-SafeTensors-only, ~20 GB resident, and explicitly
 * excludes GGUF quantization as "a known open issue"; Llama is the most-exercised family and its
 * `KLlamaJava.loadGGUF` facade is quantization-aware. See docs/PERF-LOGBOOK.md Phase 0 notes.
 * https://huggingface.co/unsloth/Llama-3.2-1B-Instruct-GGUF
 * https://huggingface.co/unsloth/Llama-3.2-3B-Instruct-GGUF
 */
@Immutable
data class SkaiNetCatalogModel(
    val id: LlmModel,
    val name: String,
    val fileName: String,
    val repo: String,
    val bytes: Long,
    val sha256: String,
    val quantization: String = SkaiNetModel.QUANTIZATION,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"

    fun modelDir(): String = parentPath(destPath())

    fun destPath(): String = joinPath(joinPath(SkaiNetModels.dir(), "skainet-llama"), fileName)

    fun partialPath(): String = destPath() + ".partial"

    fun ownerMarkerPath(): String = joinPath(modelDir(), ".edgetranslator")

    fun isOnDisk(): Boolean = Platform.fileSize(destPath()) == bytes

    fun ownedByApp(): Boolean = Platform.exists(ownerMarkerPath())

    fun markOwned() {
        Platform.mkdir(modelDir())
        Platform.writeText(ownerMarkerPath(), "")
    }

    fun removeFromDisk() {
        Platform.delete(destPath())
        Platform.delete(partialPath())
        Platform.deleteRecursively(modelDir())
    }
}

object SkaiNetModels {
    fun dir(): String = Platform.modelsDir()

    val Fast = SkaiNetCatalogModel(
        id = LlmModel.Fast,
        name = "Llama 3.2 1B Instruct",
        fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        repo = "unsloth/Llama-3.2-1B-Instruct-GGUF",
        bytes = 807_694_368L,
        sha256 = "3f5a22426976ab26cfe84dba63c1d08391717abb1af893e10f1b2968d862dcc1",
    )
    val Precise = SkaiNetCatalogModel(
        id = LlmModel.Precise,
        name = "Llama 3.2 3B Instruct",
        fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        repo = "unsloth/Llama-3.2-3B-Instruct-GGUF",
        bytes = 2_019_377_696L,
        // Verify during Phase 1 implementation against the actual published asset.
        sha256 = "",
    )
    val all = listOf(Fast, Precise)

    fun of(id: LlmModel): SkaiNetCatalogModel = if (id == LlmModel.Precise) Precise else Fast
}

object SkaiNetModel {
    const val VERSION = "1.0"
    const val QUANTIZATION = "Q4_K_M"
    const val MAX_NUM_TOKENS = 2_048
}
