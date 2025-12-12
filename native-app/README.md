# Go Zero Native - Setup Guide

This is a native Android application for "Go Zero", a voice assistant for Home Assistant.

## Prerequisites
- Android Studio Hedgehog or newer.
- JDK 17 (embedded in Android Studio).

## Setup Instructions

1.  **Open the Project**:
    - Open Android Studio.
    - Select **Open** and navigate to the `native-app` folder inside this repository.

2.  **Add the TFLite Model**:
    - You MUST provide a TensorFlow Lite model for wake word detection.
    - Rename your model file to **`model.tflite`**.
    - Place it in **`app/src/main/assets/`**.
    - *Note: The model should be trained to detect "go" and "zero" or your specific wake word.*

3.  **Build and Run**:
    - Sync the project with Gradle files.
    - Run the app on an Android device (Emulator might not support audio input correctly).

4.  **Configuration**:
    - Grant Microphone permission when prompted.
    - Enter your Home Assistant URL (e.g., `http://192.168.1.5:8123`) and Long-Lived Access Token in the app settings.
    - Tap "Save Settings".
    - Tap "Start Listening".

## Architecture
- **MainActivity**: UI for configuration and status.
- **WakeWordService**: Foreground service that keeps the audio classifier running.
- **TFLiteClassifier**: Uses TensorFlow Lite Task Library to analyze audio stream.
- **HomeAssistantClient**: Sends commands to Home Assistant's Conversation API.

---

# Developer Guide (For Web Developers)

If you are coming from a React/Next.js background, here is how this project maps to what you know.

## 1. Project Structure Map

| Android File/Folder | Web Equivalent | Purpose |
| :--- | :--- | :--- |
| **`build.gradle.kts`** (Module: app) | `package.json` | Where you define dependencies (libraries) and app config (version, ID). |
| **`AndroidManifest.xml`** | `index.html` + `manifest.json` | Declares app name, permissions (Mic, Internet), and listing of "Activities" (Pages). |
| **`MainActivity.kt`** | `App.js` / `page.js` | The entry point. Renders the UI and holds the main state. |
| **`WakeWordService.kt`** | `ServiceWorker` / Background Job | Runs the listening loop *even when the app is closed/minimized*. |
| **`TFLiteClassifier.kt`** | `utils/aiModel.js` | Helper class that wraps the TensorFlow logic. |
| **`res/values/strings.xml`** | `locales/en.json` | Verification of strings (keeping text out of code). |

## 2. The UI: Jetpack Compose ≈ React

We use **Jetpack Compose**, which is Google's modern, declarative UI toolkit. It is extremely similar to React.

### Comparison
*   **Component** → `@Composable fun MyWidget() { ... }`
*   **State** → `var text by remember { mutableStateOf("") }` (Like `useState`)
*   **Side Effects** → `LaunchedEffect` (Like `useEffect`)
*   **Props** → Function Arguments (e.g., `fun Greeting(name: String)`)

### Example `MainActivity.kt`:
```kotlin
@Composable
fun MainScreen() {
    // 1. State (useState)
    var isListening by remember { mutableStateOf(false) }

    // 2. UI Tree (JSX-like)
    Column {
        Text("Status: $isListening")
        Button(onClick = { isListening = !isListening }) {
            Text("Toggle")
        }
    }
}
```

## 3. Data Flow

Since `WakeWordService` runs in the background and `MainActivity` runs the UI, they communicate via **DataStore** (persistent storage, like `localStorage`).

1.  **Service**: Hears "Go Zero" → Writes "Go Zero" to `last_command` in DataStore.
2.  **Activity**: Observes `last_command` (via Reactive Flow) → Updates UI automatically.

## 4. Common Maintenance Tasks

### How to add a library?
Edit `app/build.gradle.kts` in the `dependencies { }` block.
*   *Web*: `npm install axios`
*   *Android*: Add `implementation("com.squareup.retrofit2:retrofit:2.9.0")` then click **Sync Now**.

### How to change the Model?
1.  Train a new `.tflite` model.
2.  Replace `app/src/main/assets/model.tflite`.
3.  Update the labels list in `TFLiteClassifier.kt` if the classes changed.

### How to change the UI?
Edit `MainActivity.kt`. The preview on the right side of Android Studio will update (like Hot Reload).

### How to debug?
Use **Logcat** (bottom tab in Android Studio).
*   Filter: `package:mine` to see your app's logs.
*   We use tags: `STT` (Speech to Text), `TFLite` (Model), `HA` (Home Assistant).
