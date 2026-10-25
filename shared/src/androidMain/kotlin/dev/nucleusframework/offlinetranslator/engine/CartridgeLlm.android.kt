package dev.nucleusframework.offlinetranslator.engine

/**
 * Android actual — a stub for now. The SmolLM2 generator cartridge publishes jvm + apple
 * targets only; pulling its `-jvm` artifacts into the Android classpath would also drag in
 * JVM variants of skainet-transformers next to the android ones. Once a cartridge with an
 * android target exists, this becomes the same few lines as CartridgeLlm.jvm.kt.
 */
internal actual class CartridgeLlm actual constructor() {
    actual suspend fun open(): LlmAccelerator =
        throw UnsupportedOperationException(
            "The cartridge engine is desktop-only in this demo — the SmolLM2 cartridge publishes no Android target yet",
        )

    actual suspend fun generate(
        system: String,
        user: String,
        onPartial: (String) -> Unit,
    ): String = throw UnsupportedOperationException("Cartridge engine is desktop-only in this demo")

    actual fun close() {}
}
