package dev.nucleusframework.offlinetranslator.bench

import dev.nucleusframework.offlinetranslator.domain.LlmBackend
import dev.nucleusframework.offlinetranslator.domain.LlmModel
import dev.nucleusframework.offlinetranslator.domain.SkaiNetFamily
import dev.nucleusframework.offlinetranslator.engine.CartridgeTranslator
import dev.nucleusframework.offlinetranslator.engine.GemmaModels
import dev.nucleusframework.offlinetranslator.engine.GemmaTranslator
import dev.nucleusframework.offlinetranslator.engine.LlmRuntime
import dev.nucleusframework.offlinetranslator.engine.SkaiNetModels
import dev.nucleusframework.offlinetranslator.engine.SkaiNetTranslator
import dev.nucleusframework.offlinetranslator.engine.TranslationMode
import dev.nucleusframework.offlinetranslator.engine.TranslationRequest
import dev.nucleusframework.offlinetranslator.engine.TranslationResult
import dev.nucleusframework.offlinetranslator.engine.Translator
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * Headless benchmark over the app's own Translator code path. Usage:
 *
 * ./gradlew :bench:run --args="--engine=skainet --family=llama --tier=fast"
 * ./gradlew :bench:run --args="--engine=litert"
 *
 * Flags: --engine=skainet|litert|cartridge (required), --family=llama|gemma (skainet only, default llama),
 * --tier=fast|precise (default fast), --runs=3, --warmup=1, --mode=translate|proofread.
 *
 * Notes on fairness, recorded here so the logbook rows stay honest:
 * - LlmBackend is forced to Cpu for both engines (SKaiNET is CPU-only; this also keeps the
 *   LiteRT Linux GPU worker from forking a subprocess the meter couldn't see).
 * - SKaiNET decodes greedily (temp=0); LiteRT's NativeLlm pins topK=1, which is also greedy.
 * - "decode steps/s": one onPartial callback per decode step. For SKaiNET that is exactly one
 *   token; LiteRT may batch tokens per callback, so compare engines on chars/s, and use
 *   steps/s as tok/s only within the SKaiNET family.
 */
fun main(args: Array<String>) {
    val flags = args.mapNotNull { arg ->
        arg.removePrefix("--").split('=', limit = 2).takeIf { it.size == 2 && arg.startsWith("--") }
    }.associate { (k, v) -> k to v.lowercase() }

    val engine = flags["engine"] ?: fail("--engine=skainet|litert is required")
    val family = if (flags["family"] == "gemma") SkaiNetFamily.GEMMA else SkaiNetFamily.LLAMA
    val tier = if (flags["tier"] == "precise") LlmModel.Precise else LlmModel.Fast
    val runs = flags["runs"]?.toIntOrNull() ?: 3
    val warmup = flags["warmup"]?.toIntOrNull() ?: 1
    val mode = if (flags["mode"] == "proofread") TranslationMode.Proofread else TranslationMode.Translate

    LlmRuntime.preference = LlmBackend.Cpu
    LlmRuntime.skainetFamily = family

    val (translator: Translator, modelName: String, modelPath: String, modelBytes: Long) = when (engine) {
        "skainet" -> {
            val m = SkaiNetModels.of(family, tier)
            Setup(SkaiNetTranslator(), "${m.name} ${m.quantization}", m.destPath(), m.bytes)
        }
        "litert" -> {
            val m = GemmaModels.of(tier)
            Setup(GemmaTranslator(), m.name, m.destPath(), m.bytes)
        }
        // Self-contained llm/v1 cartridge: no model file to check — it opens its own pinned
        // snapshot (model load time = cartridge open time, measured by preload below). Which
        // cartridge runs follows the app's own switch: EDGETRANSLATOR_CARTRIDGE=smollm2, else
        // the Llama-3.2-1B default (see CartridgeLlm.jvm.kt).
        "cartridge" -> {
            val name = if (System.getenv("EDGETRANSLATOR_CARTRIDGE")?.trim()?.lowercase() == "smollm2") {
                "SmolLM2-135M-Instruct cartridge"
            } else {
                "Llama-3.2-1B-Instruct Q4_K_M cartridge"
            }
            Setup(CartridgeTranslator(), name, "cartridge:llm-v1", -1L)
        }
        else -> fail("unknown --engine=$engine (skainet|litert|cartridge)")
    }

    val onDisk = File(modelPath).length()
    if (modelBytes > 0 && onDisk != modelBytes) {
        fail(
            "model not on disk (or size mismatch): $modelPath\n" +
                "  expected $modelBytes bytes, found $onDisk — download it via the app first " +
                "(Settings -> Models) or place the file there manually.",
        )
    }

    println("engine=$engine model=$modelName")
    println("path=$modelPath")

    val meter = PeakMeter().start()
    val loadMs: Long
    val results = mutableListOf<PromptRun>()

    runBlocking {
        val t0 = System.nanoTime()
        translator.preload(modelPath)
        loadMs = (System.nanoTime() - t0) / 1_000_000
        println("model load: $loadMs ms")

        repeat(warmup) { i ->
            print("warmup ${i + 1}/$warmup... ")
            runOne(translator, BenchmarkPrompts.first(), modelPath, mode)
            println("done")
        }

        repeat(runs) { round ->
            for (prompt in BenchmarkPrompts) {
                val run = runOne(translator, prompt, modelPath, mode)
                results += run
                println(
                    "run ${round + 1}/$runs ${prompt.id}: ttft=${run.ttftMs}ms " +
                        "e2e=${run.e2eMs}ms steps=${run.decodeSteps} " +
                        "steps/s=${"%.2f".format(run.decodeStepsPerSec)} " +
                        "chars/s=${"%.1f".format(run.charsPerSec)}",
                )
            }
        }

        translator.release()
    }
    translator.close()
    meter.stop()

    val report = buildReport(engine, modelName, modelPath, loadMs, meter, results, mode)
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val safeModel = modelName.lowercase().replace(Regex("[^a-z0-9.]+"), "-")
    val outFile = File("bench/results/$stamp-$engine-$safeModel.json")
    outFile.parentFile.mkdirs()
    outFile.writeText(report.json)

    println()
    println("results written to ${outFile.path}")
    println()
    println("markdown row for docs/PERF-LOGBOOK.md:")
    println(report.markdownHeader)
    println(report.markdownRow)
}

private data class Setup(
    val translator: Translator,
    val modelName: String,
    val modelPath: String,
    val modelBytes: Long,
)

private data class PromptRun(
    val promptId: String,
    val ttftMs: Long,
    val e2eMs: Long,
    val engineLatencyMs: Long,
    val decodeSteps: Int,
    val decodeStepsPerSec: Double,
    val outputChars: Int,
    val charsPerSec: Double,
)

private suspend fun runOne(
    translator: Translator,
    prompt: BenchPrompt,
    modelPath: String,
    mode: TranslationMode,
): PromptRun {
    var firstTokenAtNs = 0L
    var callbacks = 0
    val t0 = System.nanoTime()
    val result = translator.translate(
        TranslationRequest(
            text = prompt.text,
            sourceLang = prompt.sourceLang,
            targetLang = prompt.targetLang,
            modelPath = modelPath,
            mode = mode,
            onPartial = {
                if (callbacks == 0) firstTokenAtNs = System.nanoTime()
                callbacks++
            },
        ),
    )
    val endNs = System.nanoTime()
    val ok = result as? TranslationResult.Ok
        ?: fail("translation failed for ${prompt.id}: $result")
    val e2eMs = (endNs - t0) / 1_000_000
    val ttftMs = if (firstTokenAtNs > 0) (firstTokenAtNs - t0) / 1_000_000 else e2eMs
    val decodeMs = (e2eMs - ttftMs).coerceAtLeast(1)
    return PromptRun(
        promptId = prompt.id,
        ttftMs = ttftMs,
        e2eMs = e2eMs,
        engineLatencyMs = ok.latencyMs,
        decodeSteps = callbacks,
        decodeStepsPerSec = (callbacks - 1).coerceAtLeast(0) * 1000.0 / decodeMs,
        outputChars = ok.text.length,
        charsPerSec = ok.text.length * 1000.0 / e2eMs.coerceAtLeast(1),
    )
}

private class Report(val json: String, val markdownHeader: String, val markdownRow: String)

private fun buildReport(
    engine: String,
    modelName: String,
    modelPath: String,
    loadMs: Long,
    meter: PeakMeter,
    results: List<PromptRun>,
    mode: TranslationMode,
): Report {
    val mb = 1_048_576L
    val env = linkedMapOf(
        "timestamp" to LocalDateTime.now().toString(),
        "os" to "${System.getProperty("os.name")} ${System.getProperty("os.version")}",
        "arch" to System.getProperty("os.arch").orEmpty(),
        "cpu" to cpuModel(),
        "jdk" to System.getProperty("java.version").orEmpty(),
        "skainet" to System.getProperty("bench.skainet.version").orEmpty(),
        "skainetTransformers" to System.getProperty("bench.skainetTransformers.version").orEmpty(),
        "litertlm" to System.getProperty("bench.litertlm.version").orEmpty(),
    )
    val runsJson = results.joinToString(",\n") { r ->
        """    {"promptId": ${js(r.promptId)}, "ttftMs": ${r.ttftMs}, "e2eMs": ${r.e2eMs}, """ +
            """"engineLatencyMs": ${r.engineLatencyMs}, "decodeSteps": ${r.decodeSteps}, """ +
            """"decodeStepsPerSec": ${"%.3f".format(r.decodeStepsPerSec)}, """ +
            """"outputChars": ${r.outputChars}, "charsPerSec": ${"%.2f".format(r.charsPerSec)}}"""
    }
    val envJson = env.entries.joinToString(", ") { (k, v) -> "${js(k)}: ${js(v)}" }
    val json = """
        |{
        |  "env": {$envJson},
        |  "engine": ${js(engine)},
        |  "model": ${js(modelName)},
        |  "modelPath": ${js(modelPath)},
        |  "mode": ${js(mode.name)},
        |  "loadMs": $loadMs,
        |  "peakHeapMb": ${meter.peakHeapBytes / mb},
        |  "peakRssMb": ${meter.peakRssBytes / mb},
        |  "runs": [
        |$runsJson
        |  ]
        |}
        |
    """.trimMargin()

    val header =
        "| date | engine | model | load ms | ttft ms (med) | steps/s (med) | chars/s (med) | " +
            "e2e ms (med) | peak RSS MB | notes |\n" +
            "|---|---|---|---|---|---|---|---|---|---|"
    val row =
        "| ${LocalDateTime.now().toLocalDate()} | $engine | $modelName | $loadMs | " +
            "${median(results.map { it.ttftMs.toDouble() }).toLong()} | " +
            "${"%.2f".format(median(results.map { it.decodeStepsPerSec }))} | " +
            "${"%.1f".format(median(results.map { it.charsPerSec }))} | " +
            "${median(results.map { it.e2eMs.toDouble() }).toLong()} | " +
            "${meter.peakRssBytes / mb} | " +
            "skainet=${env["skainet"]} transformers=${env["skainetTransformers"]} " +
            "litertlm=${env["litertlm"]} cpu-only |"
    return Report(json, header, row)
}

private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

private fun cpuModel(): String = runCatching {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    when {
        os.contains("mac") ->
            ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string")
                .start().inputStream.bufferedReader().readText().trim()
        os.contains("linux") ->
            File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("model name") }
                ?.substringAfter(':')?.trim().orEmpty()
        else -> ""
    }
}.getOrDefault("").ifBlank { System.getProperty("os.arch").orEmpty() }

private fun js(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

private fun fail(message: String): Nothing {
    System.err.println("bench: $message")
    exitProcess(1)
}
