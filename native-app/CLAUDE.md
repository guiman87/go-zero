# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Go Zero Native is an Android app (Kotlin + Jetpack Compose) that acts as a voice assistant for Home Assistant. It listens for the wake phrase "Go Zero" using an on-device TensorFlow Lite model, then captures a voice command via Android SpeechRecognizer and sends it to the Home Assistant Conversation API.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest
```

Development is done in Android Studio (Hedgehog or newer). JDK 17 is required (bundled with Android Studio).

**Important**: A custom TensorFlow Lite model file must be placed at `app/src/main/assets/model.tflite` before building. The model detects "go" and "zero" as separate word classes.

## Architecture

The app has four main source files in `app/src/main/java/com/guiman87/gozero/`:

- **`MainActivity.kt`** — Jetpack Compose UI for settings (HA URL, token, sensitivity, sequence timeout) and status display (last command/response). Starts/stops `WakeWordService`.
- **`WakeWordService.kt`** — Foreground service that owns the listening lifecycle: runs `TFLiteClassifier`, detects the wake phrase, triggers Android `SpeechRecognizer`, calls `HomeAssistantClient`, speaks the response via TTS, then loops back.
- **`TFLiteClassifier.kt`** — Wraps the TFLite model. Records 16kHz mono PCM audio in 200ms inference loops, applies softmax, and requires 2 consecutive confident frames (~400ms) for each word. Sequence logic: "go" must be detected first, then "zero" within a configurable timeout window (default 2000ms).
- **`HomeAssistantClient.kt`** — Retrofit 2 HTTP client targeting the Home Assistant Conversation API.

### Data flow

Settings (URL, token, sensitivity, timeout) → DataStore → WakeWordService → TFLiteClassifier (continuous audio loop) → on wake word: SpeechRecognizer → HomeAssistantClient → TTS response + DataStore update (last command/response) → repeat.

### Persistence

All user settings and last command/response are stored in AndroidX DataStore (key-value). Keys: `ha_url`, `ha_token`, `sensitivity`, `sequence_timeout`, `last_command`, `last_response`.

### Key dependencies

- Jetpack Compose + Material 3 for UI
- Retrofit 2.9.0 + Gson for HA API
- TensorFlow Lite Support 0.5.0 for on-device inference
- Accompanist Permissions 0.34.0 for runtime microphone permission
- DataStore Preferences 1.0.0 for settings

## Required Permissions

Declared in `AndroidManifest.xml`: `INTERNET`, `RECORD_AUDIO`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`. Microphone permission is requested at runtime via Accompanist.
