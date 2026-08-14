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

## (Phase 3 rows go here once the `:bench` module and `BenchmarkPrompts.kt` exist)

Still needed before a real LiteRT-LM vs. SKaiNET comparison row can be added:
- A LiteRT-LM-only baseline row (capture this first, per the integration plan).
- `SkaiNetTranslator`/`SkaiNetLlm.jvm.kt` exercised through the app's own code path (not the
  SKaiNET-transformers repo's own tests), with `NATIVE_OPTIMIZED` quantization and the FFM
  native backend active, against `Llama-3.2-1B-Instruct-Q4_K_M.gguf` (the app's actual catalog
  model — see `SkaiNetModel.kt`).
- Android on-device numbers (NEON backend via `skainet-backend-jni-cpu`) — physical device, not
  emulator.
