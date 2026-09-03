# Quantized matmul dispatch silently falls back to a broken generic kernel for rank>2 attention projections (ClassCastException: Byte cannot be cast to Float)

> Filed as https://github.com/SKaiNET-developers/SKaiNET/issues/991 (2026-08-14). See
> docs/PERF-LOGBOOK.md for how this was found while integrating SKaiNET-transformers into this app
> (Phase 0/1 of the SKaiNET-as-second-engine plan).
>
> Checked existing issues before drafting (2026-08-14): no exact duplicate found. Closely related,
> same subsystem, both by @michalharakal: **#920** (mobile kernel-availability perf cliff — output
> correct, just slow — different mechanism) and **#973** (packed-quant byte-order/transpose
> contract bugs — also different mechanism: bytes in the wrong order, vs. this issue's dispatcher
> never reaching a quant kernel at all). Worth cross-referencing both; this may or may not share a
> root cause with #973's broader "unwritten packed-quant contract" investigation.

## Summary

`DefaultCpuOpsJvm.chooseQuantizedMatmul` only dispatches to a specialized quant kernel (e.g. `Q4KMatmulKernel`) when **both operands are rank-2**. `MultiHeadAttention`'s linear projections legitimately call `matmul` with higher-rank input (`linearProject`'s own doc: input shape `[..., in]`), so during real multi-step generation the rank-2 gate declines, execution falls through to `DefaultCpuOpsBase.matmulGeneric`, and that generic kernel has no handling for packed quantized `TensorData` at all — it assumes `Float`-castable storage for both operands based solely on `a.dtype`, and throws when it hits `b`'s raw packed bytes.

This makes any `NATIVE_OPTIMIZED`-quantized GGUF model (packed, not dequantized) unusable for real generation through `OptimizedLLMRuntime`, in both `DIRECT` and `HYBRID` modes.

## Environment

- `sk.ainet:skainet-bom` 0.40.1
- `sk.ainet.transformers:skainet-transformers-bom` 0.40.2
- JDK 21.0.10 (Homebrew), macOS (darwin)
- Reproduced with and without `--add-modules jdk.incubator.vector` (i.e. both `scalar` and `panama-vector` active as the winning provider) — same crash, same site, either way.

## Repro

Model: `unsloth/Llama-3.2-1B-Instruct-GGUF` → `Llama-3.2-1B-Instruct-Q4_K_M.gguf`, loaded with `QuantPolicy.NATIVE_OPTIMIZED`.

**Path A — `KLlamaJava.loadGGUF` (DIRECT mode, the public JVM facade):**
```kotlin
val session = KLlamaJava.loadGGUF(Path.of("Llama-3.2-1B-Instruct-Q4_K_M.gguf"))
session.generate("Say hello in one short sentence.", GenerationConfig.builder().maxTokens(16).temperature(0f).build())
```

**Path B — lower-level API directly, forcing `OptimizedLLMMode.HYBRID`:**
```kotlin
val loader = DecoderGgufWeightLoader(
    randomAccessProvider = { JvmRandomAccessSource.open(path) },
    quantPolicy = QuantPolicy.NATIVE_OPTIMIZED,
    acceptedArchitectures = setOf("llama", "mistral"),
)
val rawWeights = loader.loadToMapStreaming<FP32, Float>(ctx)
val weights = DecoderGgufMemSegConverter.convert(rawWeights, ctx, quantArena)
val model = LlamaNetworkLoader.fromWeights(weights)
val runtime = OptimizedLLMRuntime(model = model, ctx = ctx, mode = OptimizedLLMMode.HYBRID, dtype = FP32::class, bos = weights.metadata.bosTokenId)
runtime.generateUntilStop(prompt = tokens, maxTokens = 16, eosTokenId = tokenizer.eosTokenId, temperature = 0f, decode = { tokenizer.decode(it) })
```

Both paths crash identically, at generation's first forward pass.

## Stack trace

```
java.lang.ClassCastException: class java.lang.Byte cannot be cast to class java.lang.Float (java.lang.Byte and java.lang.Float are in module java.base of loader 'bootstrap')
	at sk.ainet.exec.tensor.ops.DefaultCpuOpsBase.matmulGeneric$lambda$1(DefaultCpuOps.kt:726)
	at sk.ainet.lang.tensor.data.MemorySegmentTensorDataFactory.init(MemorySegmentTensorData.kt:325)
	at sk.ainet.exec.tensor.ops.DefaultCpuOpsBase.matmulGeneric(DefaultCpuOps.kt:682)
	at sk.ainet.exec.tensor.ops.DefaultCpuOpsBase.matmul$lambda$5(DefaultCpuOps.kt:601)
	at sk.ainet.exec.tensor.ops.KernelProfile.timeGeneric(KernelProfile.kt:33)
	at sk.ainet.exec.tensor.ops.DefaultCpuOpsBase.matmul(DefaultCpuOps.kt:601)
	at sk.ainet.exec.tensor.ops.DefaultCpuOpsJvm.matmul(DefaultCpuOpsJvm.kt:202)
	at sk.ainet.lang.nn.transformer.LinearProjectionKt.linearProject(LinearProjection.kt:40)
	at sk.ainet.lang.nn.transformer.MultiHeadAttention.attentionImpl(MultiHeadAttention.kt:298/300)
	at sk.ainet.lang.nn.transformer.MultiHeadAttention.onForward(MultiHeadAttention.kt:211)
	at sk.ainet.lang.nn.Module.forward(Module.kt:21)
	at sk.ainet.apps.llm.HybridTransformerBlock.directForward(HybridTransformerBlock.kt:176)
	at sk.ainet.apps.llm.HybridTransformerBlock.onForward(HybridTransformerBlock.kt:139)
	at sk.ainet.lang.nn.Module.forward(Module.kt:21)
	at sk.ainet.lang.nn.topology.MLP.forward(MLP.kt:19)
	at sk.ainet.apps.llm.OptimizedLLMRuntime.forward(OptimizedLLMRuntime.kt:104)
	at sk.ainet.apps.llm.GenerateUntilStopKt.generateUntilStop(GenerateUntilStop.kt:63)
```

## Root cause

`DefaultCpuOpsJvm.chooseQuantizedMatmul` (`skainet-backend-cpu/src/jvmMain/.../DefaultCpuOpsJvm.kt:503-517`):

```kotlin
private fun <T : DType, V> chooseQuantizedMatmul(a: Tensor<T, V>, b: Tensor<T, V>): Tensor<T, V>? {
    // Input must be FP32
    if (a.dtype != FP32::class) return null
    if (a.shape.rank != 2) return null          // <-- gate #1

    val bData = b.data
    val bShape = b.shape
    if (bShape.rank != 2) return null            // <-- gate #2
    ...
```

`linearProject` (`skainet-lang-core/.../LinearProjection.kt:40`, doc comment) explicitly documents its input as shape `[..., in]` — i.e. arbitrary leading batch/sequence dimensions are a supported, intended case for this helper, called from `MultiHeadAttention.attentionImpl` on every forward pass. Whenever that leading dimension isn't squeezed to a flat `[batch, in]` before reaching `ops.matmul`, `chooseQuantizedMatmul` declines (both rank checks are hard requirements, no reshape/flatten attempted), and `DefaultCpuOpsJvm.matmul` (line 183-202) falls through:

```kotlin
override fun <T : DType, V> matmul(a: Tensor<T, V>, b: Tensor<T, V>): Tensor<T, V> {
    chooseQuantizedMatmul(a, b)?.let { return it }
    chooseMatmul(a, b)?.let { return it }
    // ... KernelStrictness.failIfStrict { ... } — see below
    return super.matmul(a, b)   // <-- lands in matmulGeneric, which cannot handle packed data
}
```

`super.matmul` → `matmulGeneric` (`DefaultCpuOps.kt:682-733`) branches purely on `a.dtype` (`when (a.dtype) { FP32::class, FP16::class -> ... b.data.get(*bIdx) as Float ... }`), with no check on `b.dtype`/`b.data`'s actual representation — so when `b` is a packed-quantized weight tensor whose `.data.get(...)` returns a raw `Byte` from the packed block, the unconditional `as Float` cast throws.

Confirmed via the (very useful!) `-Dskainet.strict.kernels=true` diagnostic flag (`KernelStrictness.failIfStrict`), which — instead of the silent fallback — reports:
```
sk.ainet.backend.api.kernel.NoSuchKernelException: matmul (FP32 × FP32) has no SPI kernel; would silently fall back to super.matmul. Registered providers: native-ffm(priority=100, available=true), panama-vector(priority=50, available=true), scalar(priority=0, available=true)
```
Note it reports **"FP32 × FP32"**, not "FP32 × Q4_K" — confirming this isn't a missing-kernel-for-this-quant-format issue (`Q4KMatmulKernel`/`NativeQ4KMatmulKernel`/`PanamaVectorQ4KMatmulKernel` all exist and are registered) — it's that the rank-2 gate rejects the call before quant-type dispatch is even considered, and the generic fallback mislabels the situation as an ordinary FP32×FP32 op.

## Scope: not JVM-specific

The rank-2-only gate is duplicated, not JVM-only:

- `DefaultCpuOpsJvm.chooseQuantizedMatmul` (`skainet-backend-cpu/src/jvmMain/.../DefaultCpuOpsJvm.kt:503-517`)
- `DefaultCpuOpsBase.chooseQuantizedMatmulHeap` (`skainet-backend-cpu/src/commonMain/.../DefaultCpuOps.kt:530-531`) — the **shared dispatcher every non-JVM-overridden target uses** (Native/Linux/Apple/Android/wasm all fall through to this same `commonMain` base `matmul()`, since none of `androidMain`/`linuxMain`/`appleMain`/`wasmJsMain`/`wasmWasiMain` override `chooseQuantizedMatmul`-equivalent dispatch — I only found `PlatformCpuOpsFactory.*` files there, no separate dispatch override):
  ```kotlin
  protected fun <T : DType, V> chooseQuantizedMatmulHeap(a: Tensor<T, V>, b: Tensor<T, V>): Tensor<T, V>? {
      if (a.dtype != FP32::class || a.shape.rank != 2 || b.shape.rank != 2) return null
      ...
  ```

So this is a systemic dispatch-layer bug affecting every eager-execution CPU target (JVM, Native, Android, wasm), not a JVM peculiarity — anywhere `MultiHeadAttention`'s attention-projection matmul is called with a rank>2 tensor against packed-quantized weights.

The IREE-compiled path (StableHLO → IREE `.vmfb`, e.g. `llm-runtime/gemma-iree`) is architecturally unaffected — it never goes through `OptimizedLLMRuntime`/`DefaultCpuOps*` at all — but isn't a usable substitute today: it doesn't exist for Llama, and per the project's own docs the compiled path currently only runs single decoder steps with re-prefill, not a full KV-cached generation loop, even for the architectures it does cover.

## Suggested fix direction

One of:
1. `chooseQuantizedMatmul`/`chooseMatmul` reshape/flatten leading dimensions to 2D before the rank check (batch-flatten `[..., in]` → `[prod(...), in]`, restore shape after), so higher-rank attention-projection calls still hit the specialized kernels.
2. `MultiHeadAttention.attentionImpl` (or `linearProject` itself) flattens to 2D before calling `ops.matmul`, matching what the quantized dispatch path expects.
3. At minimum, `matmulGeneric` should check `b.dtype`/`b.data`'s actual representation (not just `a.dtype`) and throw a clear, actionable error (or dequantize on read) instead of an opaque `ClassCastException` — the existing `KernelStrictness` fail-fast path is exactly the right shape for this, it just needs to fire *before* falling into `matmulGeneric`, not only before `super.matmul` is reached in the happy-path-registered-provider case.

Happy to provide a minimal standalone repro project if useful — this was found while integrating SKaiNET-transformers 0.40.2 into a downstream KMP app.
