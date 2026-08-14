package dev.nucleusframework.offlinetranslator.engine

import dev.nucleusframework.offlinetranslator.domain.TranslationEngine

/**
 * Delegates to whichever engine [LlmRuntime.engine] currently selects. This is the sole
 * unqualified [Translator] binding the app injects — constructed explicitly in
 * [dev.nucleusframework.offlinetranslator.di.AppBindings.provideTranslator].
 */
class EngineSwitchingTranslator(
    private val liteRt: Translator,
    private val skaiNet: Translator,
) : Translator {
    private fun current(): Translator =
        if (LlmRuntime.engine == TranslationEngine.SkaiNet) skaiNet else liteRt

    override suspend fun translate(request: TranslationRequest): TranslationResult = current().translate(request)

    override suspend fun preload(path: String) = current().preload(path)

    override suspend fun release() {
        liteRt.release()
        skaiNet.release()
    }

    override fun close() {
        liteRt.close()
        skaiNet.close()
    }
}
