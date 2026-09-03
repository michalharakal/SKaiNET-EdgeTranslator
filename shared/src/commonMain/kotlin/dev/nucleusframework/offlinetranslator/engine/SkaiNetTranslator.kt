package dev.nucleusframework.offlinetranslator.engine

import dev.nucleusframework.offlinetranslator.domain.SkaiNetFamily
import dev.nucleusframework.offlinetranslator.platform.Platform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SKaiNET-transformers-backed [Translator] — text-only (audio/image support in
 * SKaiNET-transformers is unconfirmed; see docs/PERF-LOGBOOK.md open items). Not
 * `@ContributesBinding` directly: wired behind [EngineSwitchingTranslator] via qualifiers.
 */
class SkaiNetTranslator(
    private val exists: (String) -> Boolean = { Platform.exists(it) },
    private val now: () -> Long = { Platform.now() },
    private val family: () -> SkaiNetFamily = { LlmRuntime.skainetFamily },
) : Translator {
    private val sessionFactory: () -> SkaiNetLlm = { SkaiNetLlm() }
    private val mutex = Mutex()
    private var session: SkaiNetLlm? = null
    private var loadedPath: String? = null
    private var loadedFamily: SkaiNetFamily? = null

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val path = request.modelPath
        if (path.isBlank() || !exists(path)) return TranslationResult.Unavailable
        val audio = request.audioWav
        val image = request.image
        if ((audio != null && audio.isNotEmpty()) || (image != null && image.isNotEmpty())) {
            return TranslationResult.Unavailable
        }
        return mutex.withLock {
            try {
                val llm = ensureLoaded(path)
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

    override suspend fun preload(path: String) {
        if (path.isBlank() || !exists(path)) return
        mutex.withLock { ensureLoaded(path) }
    }

    override suspend fun release() {
        mutex.withLock { clearSession() }
    }

    override fun close() {
        clearSession()
    }

    private fun clearSession() {
        session?.close()
        session = null
        loadedPath = null
        loadedFamily = null
        LlmRuntime.report(LlmAccelerator.None)
    }

    private fun ensureLoaded(path: String): SkaiNetLlm {
        val wantFamily = family()
        val current = session
        if (current != null && loadedPath == path && loadedFamily == wantFamily) return current
        current?.close()
        session = null
        loadedPath = null
        loadedFamily = null
        val next = sessionFactory()
        try {
            val used = next.load(path, wantFamily)
            session = next
            loadedPath = path
            loadedFamily = wantFamily
            LlmRuntime.report(used)
            return next
        } catch (t: Throwable) {
            next.close()
            LlmRuntime.report(LlmAccelerator.None)
            throw t
        }
    }
}
