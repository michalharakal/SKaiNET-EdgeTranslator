package dev.nucleusframework.offlinetranslator.engine

import java.nio.file.Path
import java.util.function.Consumer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import sk.ainet.apps.kgemma.Gemma4ChatModel
import sk.ainet.apps.kllama.chat.ChatMessage
import sk.ainet.apps.kllama.chat.ChatRole
import sk.ainet.apps.kllama.chat.Llama3ChatTemplate
import sk.ainet.apps.kllama.java.GenerationConfig
import sk.ainet.apps.kllama.java.KLlamaJava
import sk.ainet.apps.kllama.java.KLlamaSession
import dev.nucleusframework.offlinetranslator.domain.SkaiNetFamily
import sk.ainet.llm.api.ChatOptions
import sk.ainet.llm.api.ChatRequest
import sk.ainet.llm.api.Message
import sk.ainet.llm.api.StreamingChatModel

/**
 * Llama runs through [KLlamaJava]'s own session facade (unchanged, already verified); Gemma runs
 * through [Gemma4ChatModel.fromGguf] (also JVM-only — `java.lang.foreign.Arena` — and a different
 * shape: [StreamingChatModel] instead of [KLlamaSession]). Exactly one of [session]/[chatModel] is
 * set after [load]; [generate] branches on which.
 */
internal actual class SkaiNetLlm actual constructor() {
    private var session: KLlamaSession? = null
    private var chatModel: StreamingChatModel? = null

    // Llama 3.2 Instruct is fine-tuned specifically for this turn structure
    // (<|start_header_id|>...<|eot_id|>); a raw "$system\n\n$user" concatenation (what this
    // used to send) reads as unstructured continuation text to the model — it neither reliably
    // follows the translate-into-{target} instruction nor learns to emit its stop token
    // (<|eot_id|>, confirmed present as the GGUF's tokenizer.ggml.eos_token_id) in that shape,
    // so generation both drifts language and runs to maxTokens instead of stopping.
    private val chatTemplate = Llama3ChatTemplate()

    actual fun load(modelPath: String, family: SkaiNetFamily): LlmAccelerator {
        session?.close()
        session = null
        chatModel?.close()
        chatModel = null
        when (family) {
            SkaiNetFamily.LLAMA -> session = KLlamaJava.loadGGUF(Path.of(modelPath))
            SkaiNetFamily.GEMMA -> chatModel = Gemma4ChatModel.fromGguf(
                path = modelPath,
                options = ChatOptions(temperature = 0f, maxTokens = SkaiNetModel.MAX_NUM_TOKENS),
            )
        }
        return LlmAccelerator.Cpu
    }

    actual suspend fun generate(
        system: String,
        user: String,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        session?.let { active ->
            // KLlamaJava.loadGGUF bakes the system prompt into the session at load time; translation
            // needs a fresh system prompt per call (source/target language, Translate vs Proofread),
            // so it is applied here instead via the model's own chat template.
            val fullPrompt = chatTemplate.apply(
                listOf(
                    ChatMessage(role = ChatRole.SYSTEM, content = system),
                    ChatMessage(role = ChatRole.USER, content = user),
                ),
            )
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
            return@withContext active.generate(fullPrompt, config, Consumer { token ->
                acc.append(token)
                onPartial(acc.toString())
            })
        }
        val model = chatModel ?: error("SkaiNetLlm.generate called before load()")
        val request = ChatRequest(messages = listOf(Message.system(system), Message.user(user)))
        val acc = StringBuilder()
        model.stream(request).collect { chunk ->
            acc.append(chunk.delta)
            onPartial(acc.toString())
        }
        acc.toString()
    }

    actual fun close() {
        session?.close()
        session = null
        chatModel?.close()
        chatModel = null
    }
}
