package dev.nucleusframework.offlinetranslator

import dev.nucleusframework.offlinetranslator.data.FileStore
import dev.nucleusframework.offlinetranslator.di.createAppGraph
import dev.nucleusframework.offlinetranslator.engine.EngineSwitchingTranslator
import dev.nucleusframework.offlinetranslator.engine.HuggingFaceModelDownloader
import kotlin.test.Test
import kotlin.test.assertIs

class AppGraphTest {

    @Test
    fun productionGraphBindsImplementations() {
        val graph = createAppGraph()
        assertIs<FileStore>(graph.store)
        // EngineSwitchingTranslator delegates to GemmaTranslator/SkaiNetTranslator per
        // UserSettings.engine (Settings -> "Engine (experimental)") — was a bare GemmaTranslator
        // before the SkaiNet engine option existed.
        assertIs<EngineSwitchingTranslator>(graph.translator)
        assertIs<HuggingFaceModelDownloader>(graph.downloader)
    }
}
