package dev.nucleusframework.offlinetranslator.engine

import dev.nucleusframework.offlinetranslator.domain.SkaiNetFamily
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.ainet.apps.kgemma.Gemma4Ingestion
import sk.ainet.apps.kllama.agent.generateUntilStop
import sk.ainet.apps.kllama.chat.ChatMessage
import sk.ainet.apps.kllama.chat.ChatRole
import sk.ainet.apps.kllama.chat.ChatTemplate
import sk.ainet.apps.kllama.chat.Gemma4ChatTemplate
import sk.ainet.apps.kllama.chat.Llama3ChatTemplate
import sk.ainet.apps.llm.InferenceRuntime
import sk.ainet.apps.llm.OptimizedLLMMode
import sk.ainet.apps.llm.OptimizedLLMRuntime
import sk.ainet.apps.llm.Tokenizer
import sk.ainet.apps.llm.tokenizer.GGUFTokenizer
import sk.ainet.apps.llm.tokenizer.TokenizerFactory
import sk.ainet.backend.api.kernel.KernelPacks
import sk.ainet.context.DirectCpuExecutionContext
import sk.ainet.exec.kernel.jni.JniMappedKernelPack
import sk.ainet.io.AndroidRandomAccessSource
import sk.ainet.lang.memory.ExperimentalMemoryApi
import sk.ainet.lang.types.FP32
import sk.ainet.models.llama.DecoderGgufWeightLoader
import sk.ainet.models.llama.LlamaNetworkLoader

private val LLAMA_FAMILY = setOf("llama", "mistral")

private val kernelsInstalled = AtomicBoolean(false)

/**
 * Installs the 0.51 view-keyed kernel tiers exactly once per process (#338 arc, mirrors
 * `KLlamaJava.ensureKernelPacksInstalled` on the JVM side): the reference + best-provider
 * FP32/prepacked kernels, and the JNI mapped pack that serves canonical packed weights — mapped
 * or un-prepacked heap — zero-copy via the NEON kernels. `DecoderGgufWeightLoader`'s default
 * `WeightForm` is MAPPED; without this install those weights fall to the decoding reference
 * kernel — correct, but dramatically slower per matmul. Format-based, not family-based — one
 * install call serves every family's weights.
 */
@OptIn(ExperimentalMemoryApi::class)
private fun ensureKernelPacksInstalled() {
    if (!kernelsInstalled.compareAndSet(false, true)) return
    KernelPacks.install()
    JniMappedKernelPack.install()
}

/**
 * Android actual. Neither [sk.ainet.apps.kllama.java.KLlamaJava] nor `Gemma4ChatModel` is usable
 * here (both JVM-only, Panama FFM `Arena`), so both families are composed directly against the
 * commonMain runtime API — [InferenceRuntime] (the interface both `OptimizedLLMRuntime` instances
 * satisfy) + [Tokenizer] + [ChatTemplate], installing `skainet-backend-jni-cpu`'s NEON kernels
 * explicitly (see [ensureKernelPacksInstalled] — ServiceLoader alone does not register the
 * view-keyed MAPPED dispatch path).
 */
internal actual class SkaiNetLlm actual constructor() {
    private var runtime: InferenceRuntime<FP32>? = null
    private var tokenizer: Tokenizer? = null
    private var chatTemplate: ChatTemplate? = null
    private var eosTokenId: Int = 0

    actual fun load(modelPath: String, family: SkaiNetFamily): LlmAccelerator {
        ensureKernelPacksInstalled()
        val ctx = DirectCpuExecutionContext()
        when (family) {
            SkaiNetFamily.LLAMA -> {
                val loader = DecoderGgufWeightLoader(
                    randomAccessProvider = { AndroidRandomAccessSource.open(modelPath) },
                    acceptedArchitectures = LLAMA_FAMILY,
                )
                val weights = kotlinx.coroutines.runBlocking { loader.loadToMapStreaming<FP32, Float>(ctx) }
                val model = LlamaNetworkLoader.fromWeights(weights)
                runtime = OptimizedLLMRuntime(
                    model = model,
                    ctx = ctx,
                    mode = OptimizedLLMMode.DIRECT,
                    dtype = FP32::class,
                    bos = weights.metadata.bosTokenId,
                )
                val tok = AndroidRandomAccessSource.open(modelPath).use { source ->
                    TokenizerFactory.fromGgufSource(source)
                }
                tokenizer = tok
                eosTokenId = tok.eosTokenId
                chatTemplate = Llama3ChatTemplate()
            }
            SkaiNetFamily.GEMMA -> {
                val ingestion = Gemma4Ingestion<FP32>(ctx = ctx, dtype = FP32::class)
                runtime = kotlinx.coroutines.runBlocking {
                    ingestion.loadDslRuntimeStreaming { AndroidRandomAccessSource.open(modelPath) }
                }
                // TokenizerFactory.fromGgufSource dispatches on tokenizer.ggml.model, which has no
                // "gemma4" case yet — throws UnsupportedTokenizerException. GGUFTokenizer itself is
                // already Gemma-4-aware (see its addSpacePrefix doc), so this bypasses that stale
                // allowlist the same way SKaiNET-transformers' own Gemma4ChatModel.fromGguf does.
                val tok = AndroidRandomAccessSource.open(modelPath).use { source ->
                    GGUFTokenizer.fromRandomAccessSource(source)
                }
                tokenizer = tok
                eosTokenId = tok.eosTokenId
                chatTemplate = Gemma4ChatTemplate()
            }
        }
        return LlmAccelerator.Cpu
    }

    actual suspend fun generate(
        system: String,
        user: String,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        val activeRuntime = runtime ?: error("SkaiNetLlm.generate called before load()")
        val activeTokenizer = tokenizer ?: error("SkaiNetLlm.generate called before load()")
        val activeTemplate = chatTemplate ?: error("SkaiNetLlm.generate called before load()")
        activeRuntime.reset()
        // Each family's Instruct model needs its own chat-template turn structure to reliably
        // follow the translate-into-{target} instruction and to emit its stop token — see the
        // JVM-side SkaiNetLlm.jvm.kt for the full rationale (a raw "$system\n\n$user"
        // concatenation was read as unstructured continuation text and neither followed
        // instructions nor stopped).
        val fullPrompt = activeTemplate.apply(
            listOf(
                ChatMessage(role = ChatRole.SYSTEM, content = system),
                ChatMessage(role = ChatRole.USER, content = user),
            ),
        )
        val tokens = activeTokenizer.encode(fullPrompt)
        // onToken hands back one new token at a time — onPartial's contract (see
        // NativeLlm.jvm.kt's `acc.toString()`) is the full text generated so far, which is what
        // SkaiNetTranslator/AppViewModel render as the live translation. Accumulate here so the
        // UI sees a growing string instead of being repeatedly overwritten with the latest token.
        val acc = StringBuilder()
        val result = activeRuntime.generateUntilStop(
            prompt = tokens,
            maxTokens = SkaiNetModel.MAX_NUM_TOKENS,
            eosTokenId = eosTokenId,
            temperature = 0f,
            onToken = { tokenId ->
                acc.append(activeTokenizer.decode(tokenId))
                onPartial(acc.toString())
            },
            decode = { activeTokenizer.decode(it) },
        )
        result.text
    }

    actual fun close() {
        runtime = null
        tokenizer = null
        chatTemplate = null
    }
}
