# Edge Translator

Translate offline, without a word leaving this device.

This is the [SKaiNET](https://github.com/SKaiNET-developers/SKaiNET) fork of [NucleusFramework/EdgeTranslator](https://github.com/NucleusFramework/EdgeTranslator). It adds a second, pure-Kotlin translation engine next to LiteRT-LM — see [Trying the SKaiNET engine](#trying-the-skainet-engine).

Desktop app for macOS, Windows, and Linux. The model runs on the machine. After install, text, speech, images, and favorites stay local — no outbound request. An Android app is planned.

The interface is available in the same 35 languages as the catalog, including RTL (Arabic, Hebrew, Persian).

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/translate-dark.png">
    <img src="docs/screenshots/translate-light.png" width="920" alt="Edge Translator translating the project license from English to French on-device, in 4.8 seconds.">
  </picture>
</p>

## Features

- On-device streaming translation, via [Gemma 4](https://ai.google.dev/gemma) and [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM)
- Experimental second engine: [SKaiNET](https://github.com/SKaiNET-developers/SKaiNET), a pure-Kotlin inference stack that runs GGUF models (Llama 3.2, Gemma 4) with no native vendor binary
- 35 languages (CC3M-35L / PaliGemma catalog), with automatic source-language detection
- Proofreading: the same model corrects spelling and grammar in the language of the input
- Dictation: speak up to 30 seconds, transcribed and translated on-device (Gemma 4 FLEURS, 12 languages)
- Image translation: pick a photo or a screenshot, the model reads its text and translates it on-device
- Read aloud: [Piper](https://github.com/rhasspy/piper) voices, 30 languages, downloaded on demand
- Two model profiles: **Fast** (E2B) or **Precision** (E4B)
- GPU acceleration when available, otherwise CPU
- Local favorites (search, pinning, filters)
- Model and voice downloads you control: start, pause, resume, or cancel — Settings never starts a download on its own
- First launch: resumable download, then SHA-256 verification; optional voice install
- Desktop auto-update from [GitHub Releases](https://github.com/NucleusFramework/EdgeTranslator/releases)
- About screen with open-source licenses
- Follows the system light / dark appearance

<table>
  <tr>
    <td width="50%" valign="top">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/proofread-dark.png">
        <img src="docs/screenshots/proofread-light.png" alt="Proofreading: messy phonetic English rewritten as clean prose, still on-device.">
      </picture>
      <p><strong>Proofreading.</strong> Same model, same machine. No grammar-checker account.</p>
    </td>
    <td width="50%" valign="top">
      <picture>
        <source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/favorites-dark.png">
        <img src="docs/screenshots/favorites-light.png" alt="Favorites list with pinned translations in French, English, Japanese, Arabic, German, and more, stored locally.">
      </picture>
      <p><strong>Favorites.</strong> Contracts, tickets, a line of Arabic. Searchable, pinned, on disk.</p>
    </td>
  </tr>
</table>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/screenshots/onboarding-dark.png">
    <img src="docs/screenshots/onboarding-light.png" width="920" alt="First-launch screen: choose Fast or Precision, then download the model. After that, no connection is required.">
  </picture>
</p>
<p align="center"><sub>One download. After that, the network cable can come out.</sub></p>

## Languages

| Capability | Count | Languages |
|------------|------:|-----------|
| Translation | 35 | French, English, Arabic, Bengali, Chinese, Croatian, Czech, Danish, Dutch, Filipino, Finnish, German, Greek, Hebrew, Hindi, Hungarian, Indonesian, Italian, Japanese, Korean, Māori, Norwegian, Persian, Polish, Portuguese, Romanian, Russian, Spanish, Swahili, Swedish, Telugu, Thai, Turkish, Ukrainian, Vietnamese |
| Dictation | 12 | French, English, Arabic, Chinese, German, Hindi, Italian, Japanese, Korean, Portuguese, Russian, Spanish |
| Read aloud | 30 | every catalog language except Croatian, Filipino, Japanese, Māori, Thai |

## Models

Weights are not in this repository. They are downloaded from Hugging Face on first launch (the only step that needs Internet).

| Profile | Model | Size | Repository |
|---------|--------|------|------------|
| Fast | Gemma 4 E2B IT · QAT 2/4/8-bit | ~2.6 GB | [litert-community/gemma-4-E2B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm) |
| Precision | Gemma 4 E4B IT · QAT 2/4/8-bit | ~3.7 GB | [litert-community/gemma-4-E4B-it-litert-lm](https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm) |

Recommended disk space for the Fast profile: **~3.2 GB** (weights plus install headroom).

Piper voices come from [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices) (~60–140 MB each) and are optional.

Gemma models are covered by Google’s [Gemma license](https://ai.google.dev/gemma/docs/gemma_license), which is separate from this application’s license.

## Requirements

- JDK 21 installed (to build or run from source). The build uses a JDK 21 toolchain and does not download one, so a machine with only a newer JDK will fail at configuration. Gradle itself can run on the same JDK 21.
- No Android SDK is needed for the desktop app. Building `:androidApp` does need one.
- Internet connection only to download a model, a voice, or an app update
- Microphone optional (dictation)

## Install

macOS:

```bash
curl -L https://raw.githubusercontent.com/NucleusFramework/EdgeTranslator/main/download/launch.mac | bash
```

Linux (Debian / Ubuntu):

```bash
curl -L https://raw.githubusercontent.com/NucleusFramework/EdgeTranslator/main/download/launch.linux | bash
```

Prebuilt packages (DMG, NSIS, Deb) are also on [GitHub Releases](https://github.com/NucleusFramework/EdgeTranslator/releases). Packaged builds update themselves from there.

## Running from source

```bash
./gradlew :desktopApp:run
```

Hot reload:

```bash
./gradlew :desktopApp:hotRun --auto
```

Force the install screen: pass `--onboarding` as a launch argument.

Native packages (GraalVM — DMG / Zip / NSIS / Deb):

```bash
./gradlew :desktopApp:packageGraalvmDmg    # macOS
./gradlew :desktopApp:packageGraalvmZip    # macOS updater payload
./gradlew :desktopApp:packageGraalvmNsis   # Windows
./gradlew :desktopApp:packageGraalvmDeb    # Linux
```

Pushing a `v*` tag builds these packages for Linux (x64, ARM64), macOS (Intel, Apple Silicon), and Windows (x64), then publishes them as a GitHub Release.

## Trying the SKaiNET engine

The app starts on LiteRT-LM. SKaiNET is a second engine behind an experimental switch, so nothing changes until you turn it on. It runs on desktop today and is slower than LiteRT-LM; the point is that the whole inference stack is Kotlin, so any GGUF model works and anything that breaks can be fixed here rather than waited on.

```bash
git clone https://github.com/michalharakal/SKaiNET-EdgeTranslator.git
cd SKaiNET-EdgeTranslator
./gradlew :desktopApp:run
```

Then, in the app: **Settings → Engine (experimental) → SKaiNET**, pick a family (Llama or Gemma), and download a model from the list below. To skip the clicking for one launch, set an environment variable before starting:

```bash
EDGETRANSLATOR_ENGINE=skainet EDGETRANSLATOR_SKAINET_FAMILY=gemma ./gradlew :desktopApp:run
```

Both variables are session-only overrides and never change the saved settings. Accepted values are `skainet` / `litert` and `llama` / `gemma`.

SKaiNET models are quantized GGUF files (Q4_K_M) from Unsloth's public Hugging Face repositories. No account or token is needed.

| Family | Profile | Model | Size | Repository |
|--------|---------|-------|------|------------|
| Llama | Fast | Llama 3.2 1B Instruct | ~0.8 GB | [unsloth/Llama-3.2-1B-Instruct-GGUF](https://huggingface.co/unsloth/Llama-3.2-1B-Instruct-GGUF) |
| Llama | Precision | Llama 3.2 3B Instruct | ~2.0 GB | [unsloth/Llama-3.2-3B-Instruct-GGUF](https://huggingface.co/unsloth/Llama-3.2-3B-Instruct-GGUF) |
| Gemma | Fast | Gemma 4 E2B Instruct | ~3.1 GB | [unsloth/gemma-4-E2B-it-GGUF](https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF) |
| Gemma | Precision | Gemma 4 E4B Instruct | ~5.0 GB | [unsloth/gemma-4-E4B-it-GGUF](https://huggingface.co/unsloth/gemma-4-E4B-it-GGUF) |

The engine versions are pinned in `gradle/libs.versions.toml` (`skainet` and `skainetTransformers`, kept in lockstep). Benchmarks against LiteRT-LM are collected in `docs/PERF-LOGBOOK.md`.

## Layout

```
shared/       logic, Compose UI, translation engine, data
desktopApp/   desktop entry point (Nucleus)
```

All application code lives in `shared`. The desktop module only starts the UI.

Android is planned: the shared UI is already Compose Multiplatform, but there is no shipping Android app yet.

## License

Copyright (C) 2026 Elie Gambache

This program is free software: you can redistribute it and/or modify it under the terms of the **GNU Lesser General Public License version 3** as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful, but **WITHOUT ANY WARRANTY**; without even the implied warranty of **MERCHANTABILITY** or **FITNESS FOR A PARTICULAR PURPOSE**. See the GNU Lesser General Public License for more details.

You should have received a copy of the GNU Lesser General Public License ([LICENSE](LICENSE)) and of the GNU General Public License ([COPYING](COPYING), incorporated by LGPLv3) along with this program. If not, see <https://www.gnu.org/licenses/>.
