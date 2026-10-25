package dev.nucleusframework.offlinetranslator.bench

/**
 * The fixed prompt set docs/PERF-LOGBOOK.md calls for. Frozen on purpose: results are only
 * comparable across engine/version changes while these strings never move. Add new prompts at
 * the END with new ids rather than editing existing ones.
 */
data class BenchPrompt(
    val id: String,
    val text: String,
    val sourceLang: String,
    val targetLang: String,
)

val BenchmarkPrompts: List<BenchPrompt> = listOf(
    BenchPrompt(
        id = "short-en-de",
        text = "The weather is beautiful today.",
        sourceLang = "en",
        targetLang = "de",
    ),
    BenchPrompt(
        id = "short-de-en",
        text = "Bitte schicken Sie mir die Unterlagen bis Freitag.",
        sourceLang = "de",
        targetLang = "en",
    ),
    BenchPrompt(
        id = "short-en-fr",
        text = "Where is the nearest train station?",
        sourceLang = "en",
        targetLang = "fr",
    ),
    BenchPrompt(
        id = "med-en-es",
        text = "Our team will arrive on Tuesday morning. Could you please arrange access to the " +
            "building and a meeting room for eight people? We expect the workshop to last until " +
            "the early afternoon.",
        sourceLang = "en",
        targetLang = "es",
    ),
    BenchPrompt(
        id = "med-sk-en",
        text = "Ďakujem za rýchlu odpoveď. Faktúru sme dnes uhradili a potvrdenie o platbe " +
            "posielam v prílohe. Dajte mi prosím vedieť, keď zásielka opustí sklad.",
        sourceLang = "sk",
        targetLang = "en",
    ),
    BenchPrompt(
        id = "long-en-de",
        text = "On-device translation has one great advantage: nothing you type ever leaves " +
            "your computer. The model runs locally, works without an internet connection, and " +
            "responds quickly enough for everyday use. The trade-off is that local models are " +
            "smaller than the ones running in large data centers, so for long or highly " +
            "technical documents you may still prefer a cloud service. For short messages, " +
            "travel phrases and everyday email, however, a local model is usually more than " +
            "good enough.",
        sourceLang = "en",
        targetLang = "de",
    ),
)
