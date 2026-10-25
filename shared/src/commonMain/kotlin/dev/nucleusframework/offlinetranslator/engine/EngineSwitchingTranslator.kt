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
    private val cartridge: Translator,
) : Translator {
    private fun current(): Translator = when (LlmRuntime.engine) {
        TranslationEngine.LiteRt -> liteRt
        TranslationEngine.SkaiNet -> skaiNet
        TranslationEngine.Cartridge -> cartridge
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult = current().translate(request)

    override suspend fun preload(path: String) = current().preload(path)

    override suspend fun release() {
        liteRt.release()
        skaiNet.release()
        cartridge.release()
    }

    override fun close() {
        liteRt.close()
        skaiNet.close()
        cartridge.close()
    }
}
