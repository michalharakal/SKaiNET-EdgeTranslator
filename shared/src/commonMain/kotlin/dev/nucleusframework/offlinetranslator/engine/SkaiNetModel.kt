package dev.nucleusframework.offlinetranslator.engine

import androidx.compose.runtime.Immutable
import dev.nucleusframework.offlinetranslator.domain.LlmModel
import dev.nucleusframework.offlinetranslator.domain.ModelInfo
import dev.nucleusframework.offlinetranslator.domain.SkaiNetFamily
import dev.nucleusframework.offlinetranslator.platform.Platform
import dev.nucleusframework.offlinetranslator.platform.joinPath
import dev.nucleusframework.offlinetranslator.platform.parentPath

/**
 * One GGUF entry in a [SkaiNetFamily]'s catalog — SkaiNet engine, quantization-aware (unlike
 * LiteRT-LM's `.litertlm` packaging). Llama:
 * https://huggingface.co/unsloth/Llama-3.2-1B-Instruct-GGUF /
 * https://huggingface.co/unsloth/Llama-3.2-3B-Instruct-GGUF
 * Gemma (engine 0.51.1-SNAPSHOT+, SKaiNET#1217/#1218 fixed the dense-FP32-over-mapped-storage
 * matmul gap Gemma's per-layer-embedding projection hit):
 * https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF /
 * https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF
 */
@Immutable
data class SkaiNetCatalogModel(
    val family: SkaiNetFamily,
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

    fun destPath(): String = joinPath(joinPath(SkaiNetModels.dir(), "skainet-${family.id}"), fileName)

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

    fun toInfo(now: Long) = ModelInfo(
        id = id,
        installed = true,
        installedAt = now,
        sha256 = sha256.take(8),
        path = destPath(),
        lastChecked = now,
        name = name,
        version = SkaiNetModel.VERSION,
        quantization = quantization,
        expectedBytes = bytes,
    )
}

object SkaiNetModels {
    fun dir(): String = Platform.modelsDir()

    private val llamaFast = SkaiNetCatalogModel(
        family = SkaiNetFamily.LLAMA,
        id = LlmModel.Fast,
        name = "Llama 3.2 1B Instruct",
        fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        repo = "unsloth/Llama-3.2-1B-Instruct-GGUF",
        bytes = 807_694_368L,
        sha256 = "3f5a22426976ab26cfe84dba63c1d08391717abb1af893e10f1b2968d862dcc1",
    )
    private val llamaPrecise = SkaiNetCatalogModel(
        family = SkaiNetFamily.LLAMA,
        id = LlmModel.Precise,
        name = "Llama 3.2 3B Instruct",
        fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        repo = "unsloth/Llama-3.2-3B-Instruct-GGUF",
        // Verified against the published asset's Content-Length (was 2_019_377_696 — a stale/
        // typo'd value that made isOnDisk() never match a correctly-downloaded file).
        bytes = 2_019_377_600L,
        sha256 = "6c99cc00ae910f6a532a80022cb4bc1939094527a089c29294b841c0bd87f74d",
    )

    // Verified this session: Content-Length + HF's x-linked-etag (the LFS blob's sha256; cross-
    // checked against a local `shasum -a 256` of the downloaded E2B file — exact match).
    private val gemmaFast = SkaiNetCatalogModel(
        family = SkaiNetFamily.GEMMA,
        id = LlmModel.Fast,
        name = "Gemma 4 E2B Instruct",
        fileName = "gemma-4-E2B-it-Q4_K_M.gguf",
        repo = "unsloth/gemma-4-E2B-it-GGUF",
        bytes = 3_106_738_272L,
        sha256 = "740185b21d22ceb83a11c3aa62ad5842ef32c70f6096d756bbee85a1e4ec34b8",
    )
    private val gemmaPrecise = SkaiNetCatalogModel(
        family = SkaiNetFamily.GEMMA,
        id = LlmModel.Precise,
        name = "Gemma 4 E4B Instruct",
        fileName = "gemma-4-E4B-it-Q4_K_M.gguf",
        repo = "unsloth/gemma-4-E4B-it-GGUF",
        bytes = 4_977_171_584L,
        sha256 = "85a896a047553e842f25297ee5b031d64ff30147d9c4af17b1e4b394cd1fab87",
    )

    private val byFamily: Map<SkaiNetFamily, List<SkaiNetCatalogModel>> = mapOf(
        SkaiNetFamily.LLAMA to listOf(llamaFast, llamaPrecise),
        SkaiNetFamily.GEMMA to listOf(gemmaFast, gemmaPrecise),
    )

    fun catalogFor(family: SkaiNetFamily): List<SkaiNetCatalogModel> = byFamily.getValue(family)

    fun of(family: SkaiNetFamily, tier: LlmModel): SkaiNetCatalogModel =
        catalogFor(family).first { it.id == tier }

    /** Every catalog entry across every family — used for uninstall-everything sweeps. */
    val all: List<SkaiNetCatalogModel> get() = byFamily.values.flatten()
}

object SkaiNetModel {
    const val VERSION = "1.0"
    const val QUANTIZATION = "Q4_K_M"
    const val MAX_NUM_TOKENS = 2_048
}
