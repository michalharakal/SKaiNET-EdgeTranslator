# Compose Multiplatform Application

## Handover: SKaiNET engine integration + Gemma 4 E2B investigation (as of 2026-08-15)

Picking this up on a different machine. Read this section fully before doing anything — it's the
current state, not standing project doctrine (that starts below at "Project Structure").

### What's done and merged/pushed (working, verified)

EdgeTranslator now has SKaiNET (Llama-3.2 GGUF) wired in as a second, toggleable translation
engine alongside LiteRT-LM/Gemma — select via Settings or `EDGETRANSLATOR_ENGINE=skainet|litert`.
Branch **`feat/skainet-engine-integration`** (pushed to `origin`, not yet PR'd — see
`gh pr create` when ready). Real bugs found and fixed along the way, all confirmed working
end-to-end in the actual desktop app (not just headless):

- **SKaiNET#991/#992** (PR #992, merged locally, upstream PR open): quantized matmul dispatch
  required `FloatArrayTensorData` specifically for the activation operand, silently falling
  through to a broken generic kernel for `MemorySegment`-backed activations.
- **SKaiNET#993** (same PR #992 branch, `fix/q6k-memseg-matmul-dispatch`, commit `b8c4158`):
  same dispatch functions also required `a.shape.rank >= 2`, dropping single-token decode-step
  (rank-1) activations to the same broken fallback.
- **Streaming bug** (`SkaiNetLlm.jvm.kt`/`.android.kt`): `onPartial` was being called with only
  the latest delta token instead of the accumulated text so far — the target panel stayed
  completely empty because `AppViewModel` renders `onPartial`'s argument as the full live
  translation, not something to append itself. Fixed to match `NativeLlm.jvm.kt`'s
  `onPartial(acc.toString())` pattern.
- **SIMD not activating**: `desktopApp` was pinned to GraalVM/JDK 25 (`graalvm.isEnabled = true`
  in `desktopApp/build.gradle.kts` was overriding the JVM used for `:run` regardless of the
  `java.toolchain` setting). Switched to plain JDK 21 (`graalvm.isEnabled = false` for local dev;
  re-enable before a real native-image packaging build) with `jdk.incubator.vector` explicitly
  forced via `jvmArgs` inside `project.afterEvaluate` (needed because nucleus's own plugin
  reassigns `JavaExec.jvmArgs` from its own `afterEvaluate`, registered when the plugin is
  applied — a plain `tasks.withType<JavaExec>()` block gets silently overwritten unless yours is
  registered to run *after* nucleus's, i.e. wrapped in your own `afterEvaluate`).
- **Stale patched-JVM cache**: `nucleusPatchMacJvm`'s output (`desktopApp/build/nucleus/patched-jvm/`)
  doesn't get invalidated when the JDK toolchain changes — delete that directory manually after
  changing `java.toolchain`/`javaHome`, or you'll silently keep running the old JVM.
- **Single-instance app gotcha**: relaunching `:desktopApp:run` while an old instance is still
  alive just signals the old one and exits in ~1s — always `ps aux | grep desktopApp.jar` and
  kill stale instances before relaunching, or you'll test against stale code.

`gradle/libs.versions.toml` currently pins `skainet=0.40.2` / `skainetTransformers=0.40.3` as
**local-only `mavenLocal()` builds**, not on Maven Central — carrying the #991/#992/#993 fixes
pending a real upstream release. `settings.gradle.kts` has an opt-in composite build
(`-PuseLocalSkainet=true` / `-PuseLocalSkainetTransformers=true`) as an alternative to the
mavenLocal republish cycle — **now fully working for both halves** (fixed 2026-08-15, see the
Gemma 4 handover section below for the root cause and fix — it was a project-name-vs-artifactId
mismatch, not a Gradle limitation). On a fresh machine, `~/.m2/repository` won't have these
interim builds — either re-publish from the SKaiNET clone below (`publishToMavenLocal
-PsignAllPublications=false` from `:skainet-backends:skainet-backend-cpu`) or use the composite
build for iteration instead (recommended now that both halves substitute reliably).

### Where things live (this machine's paths — reclone as siblings of `SKaiNET-EdgeTranslator` on
### a new machine, composite build assumes this exact layout)

- `SKaiNET-EdgeTranslator/` — this repo, branch `feat/skainet-engine-integration`.
- `../SKaiNET/` — durable clone, branch `fix/q6k-memseg-matmul-dispatch` (PR #992,
  `github.com/SKaiNET-developers/SKaiNET`), HEAD `b8c4158` carries both dispatch fixes.
- `../SKaiNET-transformers/` — durable clone, branch `investigate/gemma4-e2b-decode-parity`
  (pushed, no PR yet — it's a findings/diagnostics branch, not a finished fix),
  `github.com/SKaiNET-developers/SKaiNET-transformers`.

### In progress, NOT resolved: getting real Gemma 4 E2B IT running via SKaiNET

Separate from the (working, merged) Llama integration above. Full plan (this exact file may not
exist on a new machine — it's a Claude Code plan file, not part of either repo):
`~/.claude/plans/we-are-going-to-spicy-newt.md` (composite-build fix → decode investigation →
reusable facade → wire into app; the original 5-phase plan referenced in earlier handover notes,
`create-plan-to-get-golden-wreath.md`, was on a different machine and is not recoverable — this
supersedes it). **Still stuck in the decode-investigation phase** — do not skip ahead to the
facade/app-wiring phases until decode is verified correct against the real checkpoint.

**Composite build now fully fixed** (was broken before, `sk.ainet.transformers:*` silently
resolved as external mavenLocal artifacts instead of local project sources): both a nested
double-`includeBuild` issue and, more importantly, a project-name-vs-artifactId mismatch (Gradle's
automatic composite substitution matches on Gradle project path, not the `POM_ARTIFACT_ID`
`vanniktech.mavenPublish` actually publishes under — SKaiNET's own project paths happen to equal
their artifact names, SKaiNET-transformers' don't). Fixed with explicit `dependencySubstitution`
rules in `SKaiNET-EdgeTranslator/settings.gradle.kts` (keyed off each module's real
`POM_ARTIFACT_ID`) plus a `gradle.parent == null` guard in `SKaiNET-transformers/settings.gradle.kts`'s
own nested include. Verified via `:shared:dependencies` showing real `project :SKaiNET-transformers:...`
substitution, and a full `:shared:compileKotlinJvm` against the composite build.

Full evidence trail: `SKaiNET-transformers/GEMMA4_E2B_SKAINET_FINDINGS.md` (on the
`investigate/gemma4-e2b-decode-parity` branch, pushed). Read it in full before continuing —
summary of where things actually stand as of 2026-08-15:

- A complete GGUF quantized-loading subsystem for Gemma 4 already exists upstream
  (`Gemma4Ingestion`/`Gemma4WeightLoader`/`GemmaMemSegConverter`/`Gemma4ChatTemplate` in
  `llm-runtime/kgemma` + `llm-inference/gemma`), just not wired into a reusable library facade —
  the premise that Gemma-on-SKaiNET needs FP32-SafeTensors-only (~20GB) was **wrong**.
- Isolated a clean minimal repro of the bug: `<bos><|turn>model\n` (4 tokens, no `ChatSession`,
  no tool, no chat-template complexity) alone reproduces immediate top-1 `<eos>` — confirmed
  independent of `ChatSession`'s prompt construction (which was itself found and fixed to be a
  faithful match) and independent of tool-calling specifically.
- **Two real, verified bugs found and fixed** (both filed + PR'd upstream under `michalharakal`,
  neither turned out to be the (sole) cause of this symptom, both kept as real fixes regardless):
  - `SKaiNET-transformers#321`/PR #322 — `partial_rotary_factor` was hardcoded to `0.25` for any
    Gemma GGUF; the real checkpoint's own `rope.dimension_count` field says it should be `1.0`.
  - `SKaiNET-transformers#323`/PR #324 — a dead-code (confirmed via testing, unused by the live
    DSL path) shared-KV-cache clobbering bug in the legacy `Gemma4AttentionBackend`.
- **Ran the real `google/gemma-4-E2B-it` checkpoint via `transformers` for ground truth** (this
  machine's `pip` was pinned to a stale mirror capping at `transformers==4.46.3`, no `gemma4`
  support at all — `uv pip install transformers` resolved the real current PyPI,
  `transformers==5.15.0`, which does support it). Confirmed our top-1 (`<eos>`) genuinely diverges
  from the real model's top-1 (`"Here"`) on the identical input — not a coincidentally-correct
  response to a degenerate prompt.
- **Ruled out quantization as the cause** — a `GEMMA4_QUANT=fp32` toggle
  (`Gemma4ReferenceParityDiagnostic`) bypassing all packed-quant kernels produces bit-identical
  output to `NATIVE_OPTIMIZED`. This is a real algorithmic divergence, not a kernel/quantization
  artifact.
- **Layer-by-layer hidden-state diff against the real model**: chased what first looked like a
  clean single-layer bug (blk.34, the final layer, ~4× smaller post-scale magnitude than the
  reference) but ruled out every layer-34-specific hypothesis checked (KV-share owner assignment,
  PLE tensor-slice bounds, `layer_output_scale` weight loading — all confirmed correct via direct
  checks). Computing *pre-scale* magnitude ratios instead of post-scale RMS shows the real
  divergence starts much earlier (~blk.3–13, already 12–37% off) and compounds across depth —
  reframes this from "one broken operation" to "small numerical differences accumulating across
  35 layers, finally large enough to flip a close top-1 decision at the output."
- **Next step, not yet done**: instrument both the Kotlin and reference forward passes to dump
  intermediate values *inside* one early block (e.g. blk.3) — post-attention, post-FFN, and
  post-PLE separately, not just the block's final output — to find which of those three
  sub-computations introduces the first measurable divergence. Prime suspects, in order: `gelu`
  exactness (HF uses `gelu_pytorch_tanh` specifically), RMSNorm epsilon/order, `qkNorm`-before-RoPE
  ordering. The findings doc has the full reasoning and all ruled-out hypotheses in detail.

To resume: reclone all three repos as siblings per "Where things live" above (this handover's own
composite-build fix is already pushed on each repo's stated branch), download a
`gemma-4-E2B-it-Q4_K_M.gguf` (e.g. `lmstudio-community/gemma-4-E2B-it-GGUF` on Hugging Face,
~3.4GB), set `GEMMA4_E2B_MODEL_PATH`, and pick up at the findings doc's "Recommended next step."
For the ground-truth comparison specifically, use `uv` (not plain `pip`) to install
`transformers`/`torch` if `pip`'s resolved version doesn't recognize `gemma4` — see Run 5 in the
findings doc for the exact working recipe.

---

## Project Structure

```
├── shared/            # ← ALL: business logic, UI, utilities, data
└── *App/              # ← thin wrapper: entry point
```

**Rule:** All project code — business logic, UI, navigation, data, utilities — goes in the `shared` module.  
Platform modules (`androidApp`, `desktopApp`, `iosApp`, `webApp`) are thin wrappers containing only an entry point (`main()` / `Application` / `Activity`).

**Source sets:** common JS and Wasm code must be written in the `webMain` source set.

## Build & Run

| Target | Command |
|--------|---------|
| **Android** (debug APK) | `./gradlew :androidApp:assembleDebug` |
| **Desktop** (run) | `./gradlew :desktopApp:run` |
| **Desktop** (hot reload) | `./gradlew :desktopApp:hotRun --auto` |
| **Compose stability dump** | `./gradlew :shared:compileKotlinJvm :shared:stabilityDump` |
| **Compose stability check** | `./gradlew :shared:compileKotlinJvm :shared:stabilityCheck` |

## Build Verification Order

When checking correctness, compile in this order (skip targets not present in the project):

1. **JVM** — fastest feedback loop
2. **Android**
3. **Wasm / JS**
4. **iOS** — slowest, compile last

## Library Selection

When searching for a Kotlin Multiplatform library to solve a task:

1. Check **[kmp-awesome](https://raw.githubusercontent.com/terrakok/kmp-awesome/refs/heads/master/README.MD)** for curated recommendations.
2. Query **[klibs.io MCP](https://api.klibs.io/mcp)** to verify dependency metadata:
   - Supported targets
   - Maven coordinates
   - Latest / latest stable versions
   - License
   - Maintenance / activity signals
   - Comparable alternatives

## Dependency Management

- Use **version catalog** (`gradle/libs.versions.toml`) for all dependencies.

## Kotlin Documentation (MCP)

Use the Kotlin Documentation MCP for API lookups and reference: **[mcp.kotlinlang.org](https://mcp.kotlinlang.org)**

## MCP Tools

- **[MCP IDEA](https://plugins.jetbrains.com/plugin/22372-kotlin-ide-mcp)** — ask the user to connect for IDE-level code analysis, refactoring, and inspections.
- **[klibs.io MCP](https://api.klibs.io/mcp)** — KMP dependency metadata.
- **[mcp.kotlinlang.org](https://mcp.kotlinlang.org)** — Kotlin documentation.
- **[Compose Hot Reload MCP](https://kotlinlang.org/docs/multiplatform/compose-hot-reload.html#mcp-server-for-ai-agents)** — when the desktop app is running with `hotRun --auto`, connect to the `hotMcpServer` Gradle task to interact with the running app:
  - `reload` — recompiles and hot-reloads changed classes
  - `take_screenshot` — captures the current state of an application window
  - `get_semantic_tree` — returns the Compose semantic tree (UI structure)
  - `get_logs` — returns recent log output from the running application
  - `click`, `type_text`, `scroll` — simulate user input to test interactive flows

## Skills

Prefer using existing skills for development tasks. Recommended skills: **[chrisbanes/skills](https://github.com/chrisbanes/skills)**

> 💡 **Tip:** When a task could benefit from a skill or MCP tool, ask the user to install/enable the relevant skill or MCP server first.

## Platform-Specific Code

When platform-specific implementation is needed:

1. **First, try to find a ready-made library** with KMP support.
2. If no ready solution exists, use **`expect` / `actual`** for platform-specific implementations:
   - Interfaces and `expect` declarations — in `commonMain`
   - `actual` implementations — in `androidMain`, `jvmMain`, `iosMain`, `webMain`

## Code Quality & Review

### Automated Checks

Before considering a task complete, the AI agent must:

1. **Analyze** code via `IDEA MCP` (call hierarchy, symbol search, refactoring).
2. **Compile** in order: JVM → Android → Wasm/JS → iOS (skipping absent targets).

## General Guidelines

1. **Kotlin Multiplatform First** — write code that works on all target platforms. Avoid platform-specific code in `commonMain`.
2. **Compose Multiplatform** — use Compose for UI on all platforms.
3. **Coroutines & Flow** — use Kotlin Coroutines and Flow for asynchronous operations and reactive data.
4. **Keep it Simple** — don't overcomplicate. Choose the simplest working approach.
5. **Ask Before Complex Changes** — if a task requires significant architectural decisions, ask the user.