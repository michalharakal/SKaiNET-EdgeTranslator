package dev.nucleusframework.offlinetranslator.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.ainet.apps.kllama.agent.generateUntilStop
import sk.ainet.apps.llm.OptimizedLLMMode
import sk.ainet.apps.llm.OptimizedLLMRuntime
import sk.ainet.apps.llm.Tokenizer
import sk.ainet.apps.llm.tokenizer.TokenizerFactory
import sk.ainet.context.DirectCpuExecutionContext
import sk.ainet.io.AndroidRandomAccessSource
import sk.ainet.io.model.QuantPolicy
import sk.ainet.lang.types.FP32
import sk.ainet.models.llama.DecoderGgufWeightLoader
import sk.ainet.models.llama.LlamaNetworkLoader

private val LLAMA_FAMILY = setOf("llama", "mistral")

/**
 * Android actual. [sk.ainet.apps.kllama.java.KLlamaJava] is JVM-only (Panama FFM `Arena`,
 * unavailable on ART), so this mirrors its internals directly against the commonMain runtime API,
 * picking up `skainet-backend-jni-cpu`'s NEON kernels transitively via ServiceLoader.
 */
internal actual class SkaiNetLlm actual constructor() {
    private var runtime: OptimizedLLMRuntime<FP32>? = null
    private var tokenizer: Tokenizer? = null
    private var eosTokenId: Int = 0

    actual fun load(modelPath: String): LlmAccelerator {
        val ctx = DirectCpuExecutionContext()
        val loader = DecoderGgufWeightLoader(
            randomAccessProvider = { AndroidRandomAccessSource.open(modelPath) },
            quantPolicy = QuantPolicy.NATIVE_OPTIMIZED,
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
        return LlmAccelerator.Cpu
    }

    actual suspend fun generate(
        system: String,
        user: String,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        val activeRuntime = runtime ?: error("SkaiNetLlm.generate called before load()")
        val activeTokenizer = tokenizer ?: error("SkaiNetLlm.generate called before load()")
        activeRuntime.reset()
        val fullPrompt = "$system\n\n$user"
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
    }
}
