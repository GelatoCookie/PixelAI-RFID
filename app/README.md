# Zebra RFID AI - Android Application & Gemini AI Assistant

An Android application demonstrating production-ready integration with the **Zebra RFID API3 SDK** (`com.zebra.rfid.api3`) alongside **Gemini 3.8 AI Assistant** built with **Jetpack Compose Material 3**.

---

## 📱 Application Screenshots

| RFID Inventory Scanner | Gemini AI Code Assistant | Gemini AI Code Response |
| :---: | :---: | :---: |
| ![RFID Scanner](../docs/screenshots/scanner_screen.png) | ![Gemini AI Screen](../docs/screenshots/ai_screen.png) | ![Gemini Response](../docs/screenshots/ai_response.png) |

---

## ✨ Features

- **Zebra RFID SDK Integration (`rfidapi3lib-2.0.5.292.aar`)**
  - Thread-serialized background SDK execution (`ExecutorService`) preventing UI thread blocking.
  - Automatic transport scanning across available reader interfaces.
  - Reader discovery, auto-connect, and region configuration (`USA` regulatory default).
- **Lifecycle-Aware Suspend & Resume**
  - **Suspend**: Automatically disconnects reader and disposes `Readers` on `ON_PAUSE` / `ON_STOP`.
  - **Resume**: Re-initializes SDK, discovers readers, and reconnects seamlessly on `ON_RESUME` / `ON_START`.
- **Real-Time RFID Tag Inventory**
  - Displays unique tag count, total read count, live scan status badge, and EPC tag items.
  - Calculates per-tag read counts and peak RSSI values (`dBm`).
  - Physical handheld trigger support (starts inventory on press, stops inventory on release).
  - Real-time search/filter by EPC tag ID.
- **Audible Tone Feedback**
  - `ToneGenerator` audio beeps for reader connection, disconnection, and new tag discovery.
- **Gemini 3.8 AI Assistant**
  - **Code Assistant**: Ask questions or request code snippet explanations for Zebra RFID SDK APIs (`RFIDReader`, `Readers`, `RfidEventsListener`, `TagData`).
  - **Inventory Analysis**: Analyzes active inventory tag lists directly with Gemini AI.
  - **Response Caching**: In-memory and persistent `SharedPreferences` caching for instant AI responses on repeated queries.

---

## 🛠️ Project Architecture

```
app/src/main/java/com/zebra/rfid/ai/
├── MainActivity.kt         # Entry point, Bluetooth/Location permissions & LifecycleObserver
├── MainAppScreen.kt        # TopAppBar & Bottom NavigationBar (Scanner vs Gemini AI)
├── RfidScreen.kt           # Material 3 UI for RFID Connection, Stats, & Tag List
├── RfidViewModel.kt        # ViewModel & StateFlow for Tag Aggregation & UI State
├── RfidHandler.kt          # Thread-serialized Zebra RFID SDK Manager (Init, Connect, Suspend, Inventory)
├── RfidUtility.kt          # Helper methods for Reader Discovery, Regions, & Events
├── ToneFeedback.kt         # Audio feedback generator
├── RfidAiScreen.kt         # Gemini AI UI with Preset Prompts & Code Explanations
└── RfidAiViewModel.kt      # Firebase Gemini 3.8 AI ViewModel with Local Caching
```

---

## 🚀 Getting Started

### Prerequisites
- **Android Studio** 2026.1+ with JDK 19+.
- **Android Device**: Zebra TC701 / RFD40 / RFD8500 or Android device running API 35+.
- **Permissions**: Bluetooth (`BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`) and Location.

### Building & Running
1. Clone the repository:
   ```bash
   git clone https://github.com/GelatoCookie/Rfid-AI.git
   cd Rfid-AI
   ```
2. Build the debug APK:
   ```bash
   ./gradlew app:assembleDebug
   ```
3. Install and run on device:
   ```bash
   ./gradlew app:installDebug
   ```

---

## 📄 License
Licensed under the Apache License, Version 2.0.
