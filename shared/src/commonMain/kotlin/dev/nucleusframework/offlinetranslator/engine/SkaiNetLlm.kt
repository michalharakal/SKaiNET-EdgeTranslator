package dev.nucleusframework.offlinetranslator.engine

/**
 * SKaiNET-transformers session for a Llama-family GGUF model — see [SkaiNetTranslator].
 * Always reports [LlmAccelerator.Cpu]: SKaiNET has no GPU/NPU backend today.
 */
internal expect class SkaiNetLlm() {
    fun load(modelPath: String): LlmAccelerator

    suspend fun generate(
        system: String,
        user: String,
        onPartial: (String) -> Unit = {},
    ): String

    fun close()
}
