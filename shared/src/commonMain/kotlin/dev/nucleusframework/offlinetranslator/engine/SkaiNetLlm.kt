package dev.nucleusframework.offlinetranslator.engine

import dev.nucleusframework.offlinetranslator.domain.SkaiNetFamily

/**
 * SKaiNET-transformers session for a GGUF model from any [SkaiNetFamily] — see [SkaiNetTranslator].
 * Always reports [LlmAccelerator.Cpu]: SKaiNET has no GPU/NPU backend today.
 */
internal expect class SkaiNetLlm() {
    fun load(modelPath: String, family: SkaiNetFamily): LlmAccelerator

    suspend fun generate(
        system: String,
        user: String,
        onPartial: (String) -> Unit = {},
    ): String

    fun close()
}
