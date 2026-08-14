package dev.nucleusframework.offlinetranslator.engine

import java.nio.file.Path
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.ainet.apps.kllama.java.GenerationConfig
import sk.ainet.apps.kllama.java.KLlamaJava
import sk.ainet.apps.kllama.java.KLlamaSession

internal actual class SkaiNetLlm actual constructor() {
    private var session: KLlamaSession? = null

    actual fun load(modelPath: String): LlmAccelerator {
        session?.close()
        session = KLlamaJava.loadGGUF(Path.of(modelPath))
        return LlmAccelerator.Cpu
    }

    actual suspend fun generate(
        system: String,
        user: String,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        val active = session ?: error("SkaiNetLlm.generate called before load()")
        // KLlamaJava.loadGGUF bakes the system prompt into the session at load time; translation
        // needs a fresh system prompt per call (source/target language, Translate vs Proofread),
        // so it is prepended here instead, mirroring KLlamaSession's own internal construction.
        val fullPrompt = "$system\n\n$user"
        val config = GenerationConfig.builder()
            .maxTokens(SkaiNetModel.MAX_NUM_TOKENS)
            .temperature(0f)
            .build()
        // KLlamaSession.generate's Consumer receives each new token as a delta, not the
        // accumulated text — but onPartial's contract (see NativeLlm.jvm.kt's `acc.toString()`)
        // is the full text generated so far, which is what SkaiNetTranslator/AppViewModel render
        // as the live translation. Accumulate here so the UI sees a growing string instead of
        // being repeatedly overwritten with just the latest fragment.
        val acc = StringBuilder()
        active.generate(fullPrompt, config, Consumer { token ->
            acc.append(token)
            onPartial(acc.toString())
        })
    }

    actual fun close() {
        session?.close()
        session = null
    }
}
