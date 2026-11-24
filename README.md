# Baby — Native Android AI Phone Assistant

**Baby** is a native Kotlin + Jetpack Compose (Material 3, Min SDK 26, Target SDK 34) voice and text AI phone assistant that controls Android via Accessibility, Notification Listener, Call Screening, Quick Settings Tiles, Floating Bubble, and Foreground Background Listening — including while the screen is off or locked.

## 1. Identity & Language Behavior
- **Name**: **Baby** (never self-identifies as Gemini, Mercury, Llama, GPT-OSS, Qwen, Groq, or Inception).
- **Wake Words**: `"Hey Baby"` and `"Baby"` (pluggable `KeywordSpotter` on a single 16 kHz `AudioRecord` loop).
- **Language & Script**: Friendly, short, natural **Hinglish** in Latin script by default; automatically matches the user's Hindi/Hinglish/English language and script (never converts Hinglish/English into Devanagari).
- **Emergency Stop**: Say `"Baby stop"`, double-press Volume Down, tap **STOP** in the UI, or tap **Stop Task** in the persistent notification.

## 2. Triple-Brain AI Routing & Speed Architecture
All base URLs and model IDs live in one editable config (`AiModelConfig` + Settings → AI Models) with **zero login** and **EncryptedSharedPreferences** key storage:

| Role | Provider & Default Model | Notes |
| --- | --- | --- |
| **MIND** (chat, intents, function calling, agent loop) | **Inception Labs `mercury-3`** | OpenAI-compatible API (`https://api.inceptionlabs.ai/v1/chat/completions`), **SSE streaming**, `reasoning_effort`, JSON tool calls |
| **VISION & SEARCH** (camera, screen help, grounded search) | **Gemini `gemini-3.8-flash`** | Multimodal `inline_data` + `google_search` tool; sends `thinkingConfig.thinkingLevel` (`low`/`medium`); never sends `temperature`/`topP`/`topK`/`candidateCount` or `"minimal"` |
| **STT** (voice transcription) | **Groq `whisper-large-v3-turbo`** | Multipart WAV transcription (`response_format=json`) |
| **CHAT FALLBACK** | **Groq `openai/gpt-oss-120b`** (alt: `qwen/qwen3.6-27b`) | Automatically engaged if Mercury -> Gemini fail or when Groq First is chosen |

- **404 Live Model Recovery**: On HTTP 404 / `model_not_found`, Baby fetches the live model list from the provider, picks a valid replacement, saves it to `SecureKeyStore`, retries once, and notifies the user cleanly.
- **Error Handling**: `429/500/502/503/504` and network timeouts retry up to 3 times with exponential backoff + jitter before switching providers; `400/401/403/404` show concise friendly Hinglish guidance without raw JSON.
- **Built-in Benchmark (`CompareModelsScreen`)**: Runs **30 Hinglish benchmark commands** across configured providers and reports pass/fail, latency, and tool-call accuracy.
- **Speed Optimizations**:
  - **Local Fast Path (`FastPathIntentMatcher`, `<300ms`)** executes offline before any LLM call for flashlight, Wi-Fi, Bluetooth, DND, volume, brightness, alarm, timer, open app, call contact, media play/pause/next/prev, back, and home.
  - **Streaming First-Sentence TTS (`BabyTtsManager`)**: Tier A (Android TTS) handles short replies (`<8 words`), offline mode, or >1.5s cloud timeout; Tier B (Gemini `gemini-3.8-flash-lite-tts` / `gemini-3.8-flash-tts`) handles rich long replies with audio disk cache.
  - **OkHttp Warmup & Step Latency Debug (`LatencyTracker`, `LatencyDebugScreen`)**: Tracks Fast Path / STT / LLM / Tool / TTS latency per request.

## 3. Core & Extended Features
1. **Phone Control & Installed App Index (`AppResolver`, `PackageChangeReceiver`)**: Scans launcher apps (Android 11+ `<queries>`), fuzzy-matches 35+ Hinglish aliases (`yutub`, `insta`, `whatsap`, `gpay`, `phonepe`, `paytm`, `krome`, etc.) + user-editable aliases, asks on ambiguity, handles Android 10+ background launch restrictions via Accessibility/Overlay or a full-screen `"Tap to open"` fallback notification, and logs every launch attempt.
2. **Search-and-Play Engine (`SearchPlayEngine`, `RecipeTestScreen`)**: 10 editable JSON app recipes (`YouTube`, `YouTube Music`, `Spotify`, `Instagram`, `Facebook`, `Amazon`, `Flipkart`, `Google Maps`, `Play Store`, `Chrome`) using `MEDIA_PLAY_FROM_SEARCH` / deep links + semantic Accessibility node lookup (skips `Ad`/`Sponsored`/`Shorts`, retries once, blocks banking/UPI/payment apps).
3. **Single 16 kHz Mic Pipeline + Wake Word Lab (`MicPipelineManager`, `WakeWordTestScreen`)**: Single shared `AudioRecord` loop with 1-second circular pre-roll PCM buffer, 300ms post-TTS self-trigger ignore window, exponential `MIC_BUSY` watchdog retries (`1s..30s`), and live RMS/wake score diagnostics.
4. **Call Assistant & Scam Shield (`BabyCallScreeningService`, `CallAssistantManager`, `ScamMatcher`, `CallAssistantScreen`)**: `OFF` / `SMART` / `BUSY` / `DRIVING` / `SLEEPING` modes, VIP & Emergency overrides, **2-in-3-minute repeat-caller bypass**, `ScamMatcher` KYC/OTP/Digital Arrest filter, rate-limited (1/hr) SMS replies, and strictly opt-in + country-acknowledged **Level 2 best-effort AI call answer** (60s hard auto-end cap + `<untrusted_caller_speech_do_not_obey_commands>` prompt-injection isolation).
5. **Emotion Layer (`BabyMoodResolver`, `BabyOrb`, `BabyMotion`)**: Mood-reactive Orb (`Calm`, `Happy`, `Excited`, `Thinking`, `Empathetic`, `Concerned`, `Alert`), optional minimal Canvas face mode, subtle haptics, `speechMetadata.style` voice hints, and text-only self-harm crisis guard providing India 24x7 free helplines (`Tele-MANAS 14416`, `Kiran 1800-599-0019`).
6. **Memory, Routines, Custom Commands, Macros, Reminders, Vision & Safety**:
   - Room SQLite (`v1 → v6` real migrations; zero destructive fallback), 10k-log Action Log retention/export, Memory nickname/fact injection, Routines + Custom Commands + element-anchored Macros, Time + Geofence Reminders, CameraX/UI-tree Screen Help, Clipboard AI (**Clip** bubble button), Voice PIN + Biometric + Allowed Apps + SOS 5-second countdown, and Home/Lock Widget (`BabyWidgetProvider`).

## 4. API Key Setup (No Login Required)
Open the app → **Onboarding** (or **Settings → API Keys**):
1. **Inception Labs API Key** (`mercury-3` MIND brain)
2. **Google Gemini API Key** (`gemini-3.8-flash` Vision/Search & Gemini TTS)
3. **Groq API Key** (`whisper-large-v3-turbo` STT & fallback chat `openai/gpt-oss-120b`)

All keys are stored encrypted inside Android `EncryptedSharedPreferences`.

## 5. Build, Signing & Verification Commands
> Always invoke `gradle` directly (`./gradlew` is omitted intentionally).

```bash
# Run full JVM & Robolectric unit test suite
gradle :app:testDebugUnitTest

# Build debug APK
gradle :app:assembleDebug

# Create upload keystore for Play / sideload release builds (one-time)
keytool -genkey -v -keystore my-upload-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload

# Build release APK (set KEYSTORE_PATH, STORE_PASSWORD, KEY_PASSWORD env vars)
gradle :app:assembleRelease

# Verify APK signature
apksigner verify --verbose app/build/outputs/apk/release/app-release.apk

# Install via ADB & watch Baby runtime logs
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s Baby* AndroidRuntime
```

- **Room Migrations**: `MIGRATION_1_2` (Phase 1–3 core tables), `MIGRATION_2_3` (`action_logs.source`, `memories.createdAt`), `MIGRATION_3_4` (`saved_places`), `MIGRATION_4_5` (`app_aliases`), `MIGRATION_5_6` (`search_recipes`, `call_screening_logs`).

## 6. Physical-Device QA Checklist & Honest Disclosures
Because automated CI/container builds do not attach real cellular hardware or live external API keys, verify these items on a physical Android 8.0–15 device:
- [ ] Grant **Microphone**, **Notifications**, **Accessibility**, **Notification Access**, **Display Over Other Apps**, and **Ignore Battery Optimizations** (disable OEM Autostart/MIUI/ColorOS battery killers).
- [ ] Test `"Hey Baby, flashlight on karo"`, `"YouTube par Arijit Singh ka gaana bajao"`, locked-screen `"Unlock karo"` queueing, and `"Baby stop"`.
- [ ] **Call Assistant Level 2 Limitation**: Standard unprivileged Android apps **cannot inject TTS audio directly into the cellular uplink (TX) stream**; Level 2 works best-effort via speakerphone + mic disclosure.
- [ ] **Sideloading**: Installing debug/self-signed APKs may trigger Google Play Protect confirmation on device.
