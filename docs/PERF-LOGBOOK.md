# SKaiNET vs. LiteRT-LM performance logbook

One entry per benchmark run. Methodology: load time excluded from tok/s where noted; greedy /
near-greedy decoding; fixed prompts. See the SKaiNET integration plan for the full benchmark
harness design (Phase 3) — this file starts with Phase 0 spike results captured before any
harness exists, so early rows are informal and clearly labeled as such.

## 2026-08-14 — Phase 0: SKaiNET-transformers reference smoke test (informal, not the app)

Not a benchmark of EdgeTranslator — a live validation that SKaiNET-transformers' own pinned
`Qwen3ReferenceSmokeTest` actually runs end-to-end on real weights, run directly against a fresh
clone of `SKaiNET-developers/SKaiNET-transformers` (`develop` branch) to ground the Phase 0
go/no-go decision.

- **Host:** this machine (macOS/darwin), JDK 21.0.10 (Homebrew), `--enable-preview
  --add-modules jdk.incubator.vector`.
- **Engine / model:** SKaiNET-transformers 0.40.2 (SKaiNET engine 0.40.1), `llm-runtime/kllama`
  eager JVM path, Qwen3-1.7B-Q8_0.gguf (ungated, `Qwen/Qwen3-1.7B-GGUF` on Hugging Face).
- **Quant policy:** `QuantPolicy.DEQUANTIZE_TO_FP32` (the reference test's own choice, for
  correctness-pinning — **not** the `NATIVE_OPTIMIZED` packed-quant path the app actually uses).
- **Backend:** default Panama Vector CPU (`skainet-backend-cpu`) only — the FFM native backend
  (`skainet-backend-native-cpu`) was not added to this test's dependencies.
- **Command:** `./gradlew :llm-runtime:kllama:jvmTest --tests
  sk.ainet.apps.kllama.Qwen3ReferenceSmokeTest -PsmokeReference -PincludeIntegration`
- **Result:** BUILD SUCCESSFUL. Prompt "What is the capital of France?" (16 greedy steps,
  temperature 0.0) produced: `" I'm not sure, but I think it's Paris." Is this a correct` —
  coherent, on-topic continuation.
- **Throughput: 0.04 tok/s (~25 s/token).** Reported by the test itself, generation time only
  (load time excluded).

**Interpretation:** proves the eager Llama/Qwen decoder path is real and correct on this
hardware, not just documentation. The throughput number is **not representative of the app's
actual configuration** — this test deliberately skips both optimizations
(`NATIVE_OPTIMIZED` quant policy, FFM native backend) that `SkaiNetLlm.jvm.kt` (via
`KLlamaJava.loadGGUF`) actually uses. Treat this row as a floor, not an estimate — do not compare
it to LiteRT-LM numbers. The first real comparison row belongs to Phase 3's benchmark harness
running the app's own `SkaiNetTranslator` / `GemmaTranslator` against the same prompt set.

## Phase 3: `:bench` harness rows (the module now exists — `./gradlew :bench:run`)

Harness: `bench/` module driving the app's own `SkaiNetTranslator`/`GemmaTranslator` headlessly
over the frozen `BenchmarkPrompts.kt` set (6 prompts, 1 warmup + 3 rounds, medians; CPU backend
forced for both engines; greedy decode on both — SKaiNET temp=0, LiteRT topK=1). Full per-prompt
numbers land in `bench/results/<timestamp>-<engine>-<model>.json`; "steps/s" is exact tok/s for
SKaiNET (one `onPartial` per token), approximate for LiteRT (callbacks may batch tokens), so
cross-engine compare on chars/s. Host: Apple Silicon macOS, JDK 21, Vector API SIMD active.

| date | engine | model | load ms | ttft ms (med) | steps/s (med) | chars/s (med) | e2e ms (med) | peak RSS MB | notes |
|---|---|---|---|---|---|---|---|---|---|
| 2026-10-25 | skainet | Llama 3.2 1B Instruct Q4_K_M | 1745 | 1567 | 51.42 | 46.6 | 2240 | 1655 | skainet=0.53.0 transformers=0.53.0 cpu-only — **0.53.0 baseline** |
| 2026-10-25 | litert | Gemma 4 E2B IT | 2533 | 269 | 28.91 | 84.3 | 943 | 7043 | litertlm=0.14.0 cpu-only — reference engine (2.6 GB QAT model; steps/s are chunk callbacks, not tokens) |
| 2026-10-25 | skainet | Llama 3.2 1B Instruct Q4_K_M | 1411 | 1306 | 68.17 | 67.6 | 1732 | 1631 | skainet=0.62.2 transformers=0.63.1 cpu-only — **after the 0.53→0.63 upgrade** |
| 2026-10-25 | skainet | Llama 3.2 1B Instruct Q4_K_M | 394 | 1339 | 67.12 | 64.9 | 1769 | 2061 | skainet=0.62.3 transformers=0.63.1 cpu-only — **0.62.3 bump**: model load 1411 → 394 ms (buffered GGUF metadata parse + one-pass tokenizer build); decode/TTFT/e2e flat (the 0.62.3 AVX2 SGEMM lane is x86-64-only, this host is Apple Silicon). |
| 2026-10-25 | cartridge | SmolLM2-135M-Instruct cartridge | 489 | 485 | 70.19 | 96.8 | 4075 | 5394 | llm/v1 via generator-smollm2-skainet 0.1.0-SNAPSHOT — wiring proof, not a quality row: the 135M model usually rambles to the 256-token model-card budget instead of stopping after the translation (5/6 prompts hit LENGTH), so e2e is budget-bound. |
| 2026-10-25 | cartridge | Llama-3.2-1B-Instruct Q4_K_M cartridge | 1661 | 1197 | 68.18 | 69.8 | 1606 | 1846 | llm/v1 via generator-llama32-1b-skainet 0.1.0-SNAPSHOT (the app's own model, as a cartridge — now the app's default cartridge). Same decode rate as the legacy SkaiNet path (68 tok/s), slightly better TTFT/e2e, STOP-terminated like the legacy rows. The llm/v1 seam costs nothing measurable. |

**0.53.0 → 0.62.2/0.63.1 upgrade result (same machine, same prompts, same model file):**
decode throughput **51.4 → 68.2 tok/s (+33 %)**, median TTFT **1567 → 1306 ms (−17 %)**,
median end-to-end **2240 → 1732 ms (−23 %)**, model load **1745 → 1411 ms**, peak RSS flat
(~1.6 GB). Upstream credits: fused softmax/RMS-norm/SDPA FFM kernels, threaded dense kernels,
the scheduled/AVX2 dense x·Wᵀ path (engine 0.62.x) and parallel attention heads (transformers
0.54.1+). Every prompt produced the identical greedy step count before and after — outputs are
unchanged, only faster. App-side the upgrade cost: one removed `@OptIn(ExperimentalMemoryApi)`,
the Android Gemma tokenizer moving from deprecated `GGUFTokenizer` to the engine-backed
`TokenizerFactory.fromGgufSource`, and deleting the kgemma POM-leak exclude workaround
(fixed upstream in 0.63.1).

This is the real baseline the Phase 0 Qwen row above is not: the app's own code path,
`NATIVE_OPTIMIZED` quantization, FFM native backend, the actual catalog model — ~51 tok/s
decode vs. that row's 0.04 tok/s floor.

Still open:
- Android on-device numbers (NEON backend via `skainet-backend-jni-cpu`) — physical device, not
  emulator.
