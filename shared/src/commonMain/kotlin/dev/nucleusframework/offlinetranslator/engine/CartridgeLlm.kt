package dev.nucleusframework.offlinetranslator.engine

/**
 * One llm/v1 cartridge session — by default `generator-llama32-1b-skainet` (the app's own
 * Llama-3.2-1B model, as a cartridge), or `generator-smollm2-skainet` with
 * `EDGETRANSLATOR_CARTRIDGE=smollm2`. Unlike [SkaiNetLlm] there is no model file to pick: a
 * cartridge is self-contained — it knows its own pinned checkpoint, renders its own chat
 * template, and opens the snapshot from the shared SKaiNET model cache
 * (`~/.cache/skainet/models/...` or its `*_MODEL_DIR` env override). CPU only.
 *
 * Desktop-only for now: the cartridge publishes jvm + apple targets, no android — the android
 * actual throws from [open] (surfaced to the user as [TranslationResult.Error]).
 */
internal expect class CartridgeLlm() {
    /** Load the cartridge's engine (real I/O — the pinned snapshot is ~260 MB). */
    suspend fun open(): LlmAccelerator

    suspend fun generate(system: String, user: String, onPartial: (String) -> Unit = {}): String

    fun close()
}
