# Notes

An enhanced fork of [Dinico414/Notes](https://github.com/Dinico414/Notes) — a Material Design 3 Jetpack Compose Android notes app supporting text, checklists, sketches, and **on-device NPU-accelerated multilingual Speech-to-Text**.

## ✨ What's New in This Fork (`v1.2.0`)

- **NPU-Accelerated Multilingual STT (`Voz` — Parakeet TDT 0.6B v3 on LiteRT 2.3.2):**
  - Replaces CPU-bound `whisper.cpp` (`ggml-tiny.bin`) with **Desert Ant Labs `Voz` (`ai.desertant:voz:4.0.0`)** backed by **Google LiteRT `2.3.2`**.
  - Supports **25 European languages** (`bg`, `cs`, `da`, `de`, `el`, `en`, `es`, `et`, `fi`, `fr`, `hr`, `hu`, `it`, `lt`, `lv`, `mt`, `nl`, `pl`, `pt`, `ro`, `ru`, `sk`, `sl`, `sv`, `uk`) with automatic language handling, built-in punctuation & capitalization, and **80 ms word-level timestamps** grouped into timestamped transcript segments.
  - **0 MB Idle RAM:** Loads `Voz` on demand in a process-wide singleton engine and automatically unloads it after 5 minutes of inactivity.
  - **Silence-Aware Chunking:** Trims leading/trailing silence while preserving exact sample-level timeline offsets and splits long recordings at low-energy pauses.
- **Multi-Vendor NPU / GPU / CPU Hardware Acceleration (`Auto`, `NPU`, `GPU`, `CPU`):**
  - Automatically detects device SoC & vendor libraries via `NpuHardwareManager`:
    - **Qualcomm Hexagon NPU** (QNN HTP / FastRPC CDSP V66–V79)
    - **MediaTek NeuroPilot APU** (Dimensity / Helio Neuron runtime)
    - **Google Tensor TPU** (Tensor G1–G5 EdgeTPU / DarwiNN)
    - **Samsung Exynos NPU** (ENN / EDEN)
    - **HiSilicon Kirin NPU**, **Rockchip RKNN**, and **Android NNAPI HAL**
    - **Adreno / Mali OpenCL GPU** and **big.LITTLE-tuned ARM64 XNNPACK CPU** fallback.
- **Resumable Background Model Downloader & Lightweight APK (~35 MB):**
  - Removed 155 MB of bundled `ggml-tiny*.bin` weights from the APK (`release/Notes-v1.2.0-arm64-v8a.apk` is **35 MB**).
  - Downloads the `Voz` `v0.4.0` weights (`~1,187 MB`, 6 artifacts) on demand in a `dataSync` foreground service with a partial `WakeLock`, **HTTP `Range` resume**, **streaming incremental SHA-256 verification**, rolling speed (`MB/s`) & ETA, and Pause / Resume / Cancel / Delete controls in both the Audio Note sheet and Settings.
- **Optional Firebase Cloud Sync:**
  - `com.google.gms.google-services` is now applied conditionally when `app/google-services.json` is present, and `MainApplication` / `GoogleAuthUiClient` / `NotesViewModel` safely run offline when Firebase isn't configured.

## 📦 Installation

1. Download the signed `Notes-v1.2.0-arm64-v8a.apk` from [**GitHub Releases**](https://github.com/ultrapg/Notes/releases/latest).
2. Install it on an Android 10+ (`minSdk 29`, `arm64-v8a`) device.
3. Open **Settings → Speech Recognition (Voz)** or tap **Get Voz** inside any Audio Note to download the offline speech model (`~1,187 MB`, resumable with SHA-256 verification).

## 🛠️ Building from Source

```bash
# 1. Configure Android SDK path (gitignored)
echo "sdk.dir=/path/to/android-sdk" > local.properties

# 2. Run unit tests
./gradlew testDebugUnitTest

# 3. Build APK
./gradlew assembleDebug
./gradlew assembleRelease
```

### Optional Configuration

- **Release Signing (kept local, never committed):**
  Provide `NOTES_KEYSTORE_PATH`, `NOTES_KEYSTORE_PASSWORD`, `NOTES_KEY_ALIAS`, and `NOTES_KEY_PASSWORD` environment variables, or place a `keystore.properties` file at `~/.android/notes-keys/keystore.properties` (or repo root `keystore.properties`, both gitignored). If no release keystore is configured, release builds fall back to the debug keystore.
- **Firebase Cloud Sync (optional):**
  Drop your own `google-services.json` into `app/google-services.json` (gitignored) before building to enable Google Sign-In and Firestore/Storage sync.

## 🙏 Credits & Licensing

- **Upstream App:** [Dinico414/Notes](https://github.com/Dinico414/Notes)
- **Speech Recognition Engine:** [Desert Ant Labs `Voz`](https://huggingface.co/desert-ant-labs/voz) (NVIDIA Parakeet TDT 0.6B v3) on Google LiteRT (`2.3.2`). Desert Ant Labs SDKs and model weights are subject to the [Desert Ant Labs Source-Available License v1.0](https://license.desertant.com/1.0).
