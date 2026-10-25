package dev.nucleusframework.offlinetranslator.engine

import dev.nucleusframework.offlinetranslator.platform.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SKaiNET-cartridge-backed [Translator] (llm/v1 task profile) — text-only, like
 * [SkaiNetTranslator], and the same mutex/latency structure, minus the model-path gate: a
 * cartridge ships its own pinned checkpoint identity, so there is nothing to select or
 * validate app-side. Translation and proofreading both ride the same cartridge — llm/v1 takes
 * messages as data, and the two features differ only in the system prompt
 * ([buildTranslationPrompt] already handles [TranslationMode]).
 * Not `@ContributesBinding` directly: wired behind [EngineSwitchingTranslator] via qualifiers.
 */
class CartridgeTranslator(
    private val now: () -> Long = { Platform.now() },
) : Translator {
    private val mutex = Mutex()
    private var session: CartridgeLlm? = null

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val audio = request.audioWav
        val image = request.image
        if ((audio != null && audio.isNotEmpty()) || (image != null && image.isNotEmpty())) {
            return TranslationResult.Unavailable
        }
        return mutex.withLock {
            try {
                val llm = ensureOpen()
                val start = now()
                val prompt = buildTranslationPrompt(request)
                val raw = llm.generate(prompt.system, prompt.user) { partial ->
                    request.onPartial(prompt.restore(cleanModelOutput(partial)))
                }
                TranslationResult.Ok(
                    text = prompt.restore(cleanModelOutput(raw)),
                    latencyMs = now() - start,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TranslationResult.Error(e.message)
            }
        }
    }

    /** [path] is ignored — the cartridge knows its own checkpoint. */
    override suspend fun preload(path: String) {
        mutex.withLock {
            try {
                ensureOpen()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Surfaced on the first translate instead; preload is best-effort.
            }
        }
    }

    override suspend fun release() {
        mutex.withLock { clearSession() }
    }

    override fun close() {
        clearSession()
    }

    private suspend fun ensureOpen(): CartridgeLlm {
        session?.let { return it }
        val next = CartridgeLlm()
        try {
            val used = next.open()
            session = next
            LlmRuntime.report(used)
            return next
        } catch (t: Throwable) {
            next.close()
            LlmRuntime.report(LlmAccelerator.None)
            throw t
        }
    }

    private fun clearSession() {
        session?.close()
        session = null
        LlmRuntime.report(LlmAccelerator.None)
    }
}
