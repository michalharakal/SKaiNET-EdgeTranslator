package dev.nucleusframework.offlinetranslator.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.ainet.cartridge.generator.llama32.Llama32GeneratorCartridge
import sk.ainet.cartridge.generator.smollm2.SmolLm2GeneratorCartridge
import sk.ainet.cartridge.profiles.llm.LlmEngine
import sk.ainet.cartridge.profiles.llm.LlmEvent
import sk.ainet.cartridge.profiles.llm.LlmMessage
import sk.ainet.cartridge.profiles.llm.LlmRole
import sk.ainet.cartridge.profiles.llm.LlmSessionConfig

/**
 * JVM actual over the llm/v1 contract. The host side is deliberately tiny — this whole file is
 * the "SKaiNET cartridges are easy" demo: open the cartridge, open a session per turn, pass
 * messages as data, collect the event flow. The chat template (SmolLM2's ChatML) renders
 * INSIDE the cartridge; concatenating special tokens here would be the wrong layer (ADR-011).
 */
internal actual class CartridgeLlm actual constructor() {
    private var engine: LlmEngine? = null
    private var maxOutputTokens: Int = Llama32GeneratorCartridge.DEFAULT_MAX_OUTPUT_TOKENS

    actual suspend fun open(): LlmAccelerator {
        close()
        // Default is the Llama-3.2-1B cartridge (the app's own model as a cartridge — real
        // translation quality); EDGETRANSLATOR_CARTRIDGE=smollm2 keeps the tiny wiring-proof
        // cartridge. Both are llm/v1 engines; nothing below this line changes between them.
        val pick = System.getenv("EDGETRANSLATOR_CARTRIDGE")?.trim()?.lowercase()
        engine = when (pick) {
            "smollm2" -> {
                maxOutputTokens = SmolLm2GeneratorCartridge.DEFAULT_MAX_OUTPUT_TOKENS
                SmolLm2GeneratorCartridge.open()
            }
            else -> {
                maxOutputTokens = Llama32GeneratorCartridge.DEFAULT_MAX_OUTPUT_TOKENS
                Llama32GeneratorCartridge.open()
            }
        }
        return LlmAccelerator.Cpu
    }

    actual suspend fun generate(
        system: String,
        user: String,
        onPartial: (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        val active = engine ?: error("CartridgeLlm.generate called before open()")
        // llm/v1 sessions are one-shot (prompt once, collect once); the engine stays loaded.
        // maxOutputTokens stays at the cartridge's model-card default (256 for both): a small
        // model rambles past the translation when given the app's 2048 budget — measured, 5/6
        // bench prompts ran SmolLM2 to the cap — and these inputs never need more.
        val config = LlmSessionConfig(
            temperature = 0f,
            maxOutputTokens = maxOutputTokens,
        )
        active.session(config).use { session ->
            session.prompt(
                listOf(
                    LlmMessage(LlmRole.SYSTEM, system),
                    LlmMessage(LlmRole.USER, user),
                ),
            )
            // LlmEvent.Delta carries the new fragment only; onPartial's contract (see
            // NativeLlm.jvm.kt's `acc.toString()`) is the full text so far — accumulate.
            val acc = StringBuilder()
            var finalText: String? = null
            session.events.collect { event ->
                when (event) {
                    is LlmEvent.Delta -> {
                        acc.append(event.text)
                        onPartial(acc.toString())
                    }
                    is LlmEvent.Final -> finalText = event.text
                    is LlmEvent.Failed -> error("${event.error.code}: ${event.error.message}")
                    else -> Unit
                }
            }
            finalText ?: acc.toString()
        }
    }

    actual fun close() {
        engine?.close()
        engine = null
    }
}
