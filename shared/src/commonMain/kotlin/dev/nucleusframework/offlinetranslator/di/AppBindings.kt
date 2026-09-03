package dev.nucleusframework.offlinetranslator.di

import app.cash.sqldelight.db.SqlDriver
import dev.nucleusframework.offlinetranslator.data.createSqlDriver
import dev.nucleusframework.offlinetranslator.engine.EngineSwitchingTranslator
import dev.nucleusframework.offlinetranslator.engine.GemmaTranslator
import dev.nucleusframework.offlinetranslator.engine.MicRecorder
import dev.nucleusframework.offlinetranslator.engine.SkaiNetTranslator
import dev.nucleusframework.offlinetranslator.engine.Translator
import dev.nucleusframework.offlinetranslator.engine.TtsSpeaker
import dev.nucleusframework.offlinetranslator.engine.createHttpClient
import dev.nucleusframework.offlinetranslator.engine.createMicRecorder
import dev.nucleusframework.offlinetranslator.engine.createTtsSpeaker
import dev.nucleusframework.offlinetranslator.platform.IoDispatcher
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@ContributesTo(AppScope::class)
@BindingContainer
object AppBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideHttpClient(): HttpClient = createHttpClient()

    @Provides
    @SingleIn(AppScope::class)
    fun provideSqlDriver(): SqlDriver = createSqlDriver()

    @Provides
    fun provideDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Io
    fun provideIoDispatcher(): CoroutineDispatcher = IoDispatcher

    @Provides
    fun provideMicRecorder(): MicRecorder = createMicRecorder()

    @Provides
    fun provideTtsSpeaker(http: HttpClient): TtsSpeaker = createTtsSpeaker(http)

    @Provides
    @LiteRtEngine
    fun provideLiteRtTranslator(): Translator = GemmaTranslator()

    @Provides
    @SkaiNetEngine
    fun provideSkaiNetTranslator(): Translator = SkaiNetTranslator()

    @Provides
    fun provideTranslator(
        @LiteRtEngine liteRt: Translator,
        @SkaiNetEngine skaiNet: Translator,
    ): Translator = EngineSwitchingTranslator(liteRt, skaiNet)
}
