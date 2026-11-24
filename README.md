# Baby — Native Android AI Phone Assistant

**Baby** is a native Kotlin + Jetpack Compose voice and text AI phone assistant that controls the device via Accessibility, Notification Listener, Quick Settings Tile, Floating Bubble, and Foreground Background Listening — even when the screen is off or locked.

## Key Features
- **Identity & Personality**: Friendly Hinglish by default (automatically adapts to Hindi, Hinglish, or English). Always introduces itself as **Baby**.
- **Dual Wake Word**: Responds to both **"Hey Baby"** and **"Baby"** with an adjustable sensitivity slider and a 2-second cooldown to prevent false triggers. Say **"Baby stop"** (or double-press Volume Down / tap STOP) anytime to abort a running task.
- **Dual-Brain AI Routing + Auto-Fallback**:
  - **Groq (`llama-3.3-70b-versatile`)**: Fast brain for intent detection, structured function calling, and quick replies.
  - **Groq Whisper (`whisper-large-v3-turbo`)**: Accurate Hinglish / Hindi / English speech-to-text.
  - **Gemini (`gemini-3.8-flash`)**: Heavy brain for screenshot vision analysis and complex multi-step reasoning.
  - Automatic fallback switches brains seamlessly if one provider hits a rate limit or network error.
- **Autonomous Agent Loop (Max 15 Steps)**: `Observe (UI tree + optional screenshot) -> Plan -> Act -> Verify -> Repeat` with a confirmation gate before sensitive actions (payments, UPI, sending SMS/WhatsApp, deleting data, uninstalling apps).
- **Background & Locked Phone Support**:
  - `BabyForegroundService` (`START_STICKY`, partial `WakeLock`, persistent notification `"Baby is listening"`).
  - `BootReceiver` auto-restarts Baby after reboot.
  - Non-UI tasks (calls, alarms, timers, flashlight, volume, media, DND, notification reading, TTS) run while locked. UI-automation tasks queue safely, speak `"Unlock karo, kaam shuru karta hoon."`, and resume automatically upon unlock (`ACTION_USER_PRESENT`).
- **On-Device Privacy**: No login or Google Sign-In. API keys are encrypted locally using Android Keystore (`EncryptedSharedPreferences`). Chat and task logs stay in local Room SQLite.

## Project Structure
```
app/src/main/java/com/example/
├── BabyApplication.kt
├── MainActivity.kt
├── core/security/SecureKeyStore.kt
├── data/
│   ├── local/ (BabyDatabase.kt, Entities.kt, Daos.kt, HistoryRepository.kt)
│   └── remote/ (GeminiApiService.kt, GeminiModels.kt, GroqApiService.kt, GroqModels.kt)
├── di/AppModule.kt
├── domain/
│   ├── ai/ (AiRouter.kt, AgentOrchestrator.kt)
│   └── tools/ (ToolDefinitions.kt, PhoneControlExecutor.kt, StopSignalBus.kt)
├── service/
│   ├── BabyForegroundService.kt
│   ├── BabyAccessibilityService.kt
│   ├── BabyNotificationListenerService.kt
│   ├── BabyQuickSettingsTileService.kt
│   ├── FloatingBubbleController.kt
│   └── BootReceiver.kt
├── ui/
│   ├── navigation/Routes.kt
│   ├── screens/ (OnboardingScreen.kt, ChatScreen.kt, HistoryScreen.kt, SettingsScreen.kt)
│   ├── theme/ (Color.kt, Theme.kt, Type.kt)
│   └── viewmodel/BabyViewModel.kt
├── util/PermissionHelper.kt
└── voice/ (AudioRecorderManager.kt, BabyTtsManager.kt, WakeWordDetector.kt)
```

## Setup & Build Steps
1. Open the project in Android Studio (JDK 11+, Android SDK 34/36, minSdk 26).
2. Build the debug APK:
   ```bash
   gradle assembleDebug
   ```
3. Install on an Android 8.0+ (API 26+) device and complete the step-by-step **Onboarding Wizard**:
   - Grant Microphone, Notifications, and optional Phone/Contacts/SMS permissions.
   - Enable **Baby Phone Control** in Android Accessibility Settings.
   - Enable **Baby Notification Reader** in Notification Access Settings.
   - Allow **Ignore Battery Optimizations** and follow the OEM Autostart guide (Xiaomi/Oppo/Vivo/Samsung).
   - Paste your **Groq API Key** and **Gemini API Key** in Onboarding or Settings.
4. Add the **Baby** Quick Settings tile from the notification shade for one-tap voice access anywhere.

## Extended Features & How to Test

### 1. Action Log (`ActionLogInterceptor`, `ActionLogRepository`, `HistoryScreen`)
- **Schema & Interceptor**: Every tool call in the agent loop passes through `ActionLogInterceptor` storing `timestamp`, `commandText`, `toolName`, `resultText`, `targetApp`, and `source` (`voice`, `text`, `routine`, `auto-reply`).
- **How to Test**:
  1. Run a voice command (`"Flashlight on karo"`), a text command, a routine (`"Good morning"`), and an auto-reply.
  2. Open **History → Action Log**, filter by **Date (`TODAY` / `7D`)**, **Source**, **Status**, or **App**, and search by keyword.
  3. Tap the download icon to export as `baby_action_log.txt` or share as plain text; change **Auto-Delete Action Logs After** (`7 / 30 / 90 days` or `Forever`) in **Settings**.

### 2. Memory (`MemoryManager`, `PhoneControlExecutor`, `MemoryScreen`)
- **Nicknames & Top-10 Injection**: Nicknames/preferences/facts/habits are stored in Room (`memories`), up to 10 keyword-matched items are injected per LLM call, and contact nicknames resolve automatically in calls/SMS/WhatsApp.
- **How to Test**:
  1. Say `"yaad rakhna Mummy ka naam Sunita Sharma hai"` or add a `NICKNAME` (`Mummy -> 98...` or contact name) in **Settings → Manage Memories**.
  2. Ask `"Mummy ko call karo"` — Baby resolves the nickname before querying contacts.
  3. Edit/delete single memories in **Manage Memories** (or ask `"delete memory Mummy"`). Say `"Sab bhool jao"` and verify Baby asks for confirmation before wiping.

### 3. Daily Routines (`RoutineManager`, `RoutineTriggerWorker`, `RoutinesScreen`)
- **Built-ins & Triggers**: `"Good morning"` (stop alarm → weather → calendar events → notification summary), `"Good night"`, `"Leaving home"`, `"Reached home"`, plus custom routines (`MANUAL`, `TIME`, `CHARGING`, `HEADPHONES`). Sensitive actions (payments, SMS/WhatsApp, delete, uninstall) are stripped/blocked from routines.
- **How to Test**:
  1. Open **Routines**, run `"Good morning"`, or say `"routine banao: jab main bolu study mode to DND on aur brightness 20% karo"`.
  2. Tap **Edit** on a routine to set trigger mode (`TIME`, `CHARGING`, or `HEADPHONES`) and connect earphones to test headphone auto-trigger.
  3. Trigger a routine with UI steps while screen is locked — non-UI steps run immediately and UI steps queue until unlock.

### 4. Auto-Reply (`AutoReplyManager`, `AutoReplyQuickSettingsTileService`, `BabyNotificationListenerService`)
- **Modes & Safety**: Supports `Busy`, `Driving`, `Sleeping`, `Custom` (via inline notification reply or `SmsManager` SMS fallback for SMS/missed calls), optional Groq Hinglish reply, per-contact ALLOW/NEVER rules, group chat ignore (default ON), scheduled active hours, 1-reply/contact/hour rate limit, persistent `"Auto-reply ON (mode)"` notification, and a **Baby Auto-Reply** Quick Settings tile.
- **How to Test**:
  1. Enable **Auto-Reply** in **Settings** (or tap the **Baby Auto-Reply** Quick Settings tile), choose a mode (`Busy` / `Custom`), customize templates, and verify `"Auto-reply ON (...)"` appears in the foreground notification.
  2. Receive a WhatsApp/SMS notification from a non-blocked contact and confirm an auto-reply is sent once per hour and logged in **Action Log** with `[auto-reply]`.

### Phase 2 Modules & How to Test

#### 5. Notification Summary (`NotificationSummaryManager`, `ScheduledSummaryWorker`)
- **Features**: `"Notifications batao"` generates a Hinglish summary ordered **People first, Updates next, Promos last** via Groq (`summarize_notifications(scope)`). Scheduled morning/evening summaries post notifications via WorkManager (speaking aloud optional). **OTPs & banking alerts are masked by default** and never read aloud unless enabled in Settings.
- **How to Test**:
  1. Ensure **Notification Access** and **Notification Summary** are enabled in Settings.
  2. Say `"Notifications batao"` (or `"WhatsApp notifications batao"`) — verify people messages appear before promos and OTPs/bank figures show `[OTP/Bank alert masked]`.
  3. Toggle **Morning / Evening Summary** in Settings, and optionally test enabling **Allow Reading OTPs & Banking Alerts**.

#### 6. Reminders & Saved Places (`ReminderManager`, `RemindersScreen`, `BootReceiver`)
- **Features**: Natural-language reminders (`"shaam 6 baje doodh yaad dilana"`) parsed via Groq + `AlarmManager`, Geofencing location reminders (`"ghar pahunchu to bolna"`), saved **Home/Work** places (via voice `save_place`, current GPS, or interactive Map Picker), **Reminders** screen with **list, edit, delete, snooze**, notification **Snooze 10m / Done** actions, and `BootReceiver` reboot rescheduling (`set_reminder`, `list_reminders`, `delete_reminder`).
- **How to Test**:
  1. Say `"Shaam 6 baje doodh yaad dilana"` and verify it appears in the **Reminders** tab.
  2. Tap **Snooze** or **Edit** or **Delete** on the reminder card.
  3. Enable **Location Reminders** in Settings (location permission requested only then), tap **Pick Home** on the Reminders tab to save Home via GPS/Map Picker, and add a `"Ghar pahunchu to..."` reminder.

#### 7. Screen Help (`ScreenHelpManager`)
- **Features**: `"Ye kya hai?"`, `"isko translate karo"`, `"isko summarize karo"` uses the **Accessibility UI tree first**, capturing a screenshot for Gemini Vision **only when the UI tree lacks readable text and Data Saver is OFF** (`explain_screen(mode)`).
- **How to Test**:
  1. Open any text-rich app (e.g. a WhatsApp chat or Settings) and ask `"Isko summarize karo"` or `"Isko translate karo"` — confirm it uses the UI tree without capturing a screenshot.
  2. Turn **Data Saver** ON in Settings and open an image-only screen — verify Baby still restricts itself to the text tree only.

#### 8. Clipboard AI (`ClipboardAiManager`, `ClipboardActionActivity`, `FloatingBubbleController`)
- **Features**: On-demand summarize, translate, or draft-reply for copied text via voice (`read_clipboard_and_process(mode)`) or the **Clip** button on the floating bubble. Never reads clipboard in the background.
- **How to Test**:
  1. Copy any English/Hindi paragraph or message.
  2. Tap **Clip** on the Floating Bubble (or say `"Clipboard summarize karo"` / `"Clipboard ka reply draft karo"`) and pick **Summarize**, **Translate**, or **Draft Reply**.

#### 9. Camera + Vision (`CameraVisionManager`, `CameraCaptureActivity`)
- **Features**: `"Ye kya hai?"` / camera icon opens `CameraCaptureActivity`, captures one temporary photo in `cacheDir` (never saved to Gallery), sends it to Gemini Vision (`capture_and_describe(question)`), speaks the Hinglish answer, and deletes the temp file right after the API call.
- **How to Test**:
  1. Tap the Camera icon in Chat (or say `"Camera se dekho ye kya hai?"`). Grant Camera permission if prompted on first use.
  2. Confirm Baby captures one photo, speaks the Hinglish description, and leaves no photo in Gallery.

### Phase 3 Modules & How to Test

#### 10. Custom Commands (`RoutineManager` reuse, `RoutinesScreen` → Commands tab)
- **Features**: Define custom multi-action phrases (e.g. `"Movie mode"` -> DND on, brightness low, media volume 40%, open Netflix) by voice (`"Naya command banao..."`) or UI, reusing `RoutineManager` (`scheduleType = "COMMAND"`) without duplicating execution logic. Includes enable toggle and tools `create_command`, `run_command`, `list_commands`, `delete_command`.
- **How to Test**:
  1. Open **Routines → Commands**, tap **Add Movie Mode** preset (or create your own), and confirm actions appear in order.
  2. Say `"Movie mode"` or tap **Run** — verify all steps execute cleanly and log in **Action Log**.

#### 11. Macro Recording (`MacroManager`, `BabyAccessibilityService`, `RoutinesScreen` → Macros tab)
- **Features**: `"Macro record shuru karo"` captures Accessibility taps, scrolls (debounced), and text inputs (coalesced) with element identifiers (`viewId`, `targetText`, `contentDesc`, `packageName`) — never raw coordinates alone. `"Macro save karo [name]"` saves in Room and `"[name] chalao"` replays. Replay verifies each element, retries once after 750ms, and stops naming the failed step if still missing. Password/OTP/payment screens are automatically skipped with a spoken warning. UI macros queue safely while locked and run automatically after unlock. Tools: `start_macro_recording`, `stop_macro_recording`, `run_macro`, `list_macros`, `delete_macro`.
- **How to Test**:
  1. Say `"Macro record shuru karo"` (or tap **Start Macro Recording**), tap through 2–3 non-sensitive screens, then say `"Macro save karo Open Notes"` (or tap **Save**).
  2. Open a password or UPI screen while recording — confirm Baby skips the step and warns you.
  3. Trigger `"[name] chalao"` while the phone is locked to confirm it queues until unlock, or while unlocked on the wrong screen to confirm 1 retry + clear step-failure message.

#### 12. Safety Features (`SafetyManager`, `SecureKeyStore`, `StopSignalBus`)
- **Features**: Keystore-backed salted SHA-256 Voice PIN + optional `BiometricPrompt` gate before sensitive actions (payments, delete, uninstall, security settings); Allowed Apps allowlist with spoken refusal when disabled apps are blocked; automatic blocklist for banking/UPI apps, password managers, and system security screens; Panic Stop (`"Baby stop"`, Stop button, foreground notification **Stop Task**, and double-press Volume Down) halting active task, macro, and routine immediately.
- **How to Test**:
  1. Set a Voice PIN in **Settings → Safety & Emergency** (and toggle Biometric if supported), then ask Baby to uninstall an app or send money — confirm PIN/biometric prompt blocks execution until verified.
  2. Turn on **Allowed Apps Only**, add only 1–2 apps, then ask `"Open Instagram"` — verify Baby speaks the refusal aloud.
  3. Run a multi-step task/macro and say `"Baby stop"` (or tap **Stop**) to verify immediate cancellation.

#### 13. Emergency Mode (`SafetyManager`, `BabyForegroundService`)
- **Features**: `"Baby help"` or optional **Power-Button Multi-Press** (4 rapid screen ON/OFF toggles within 3.5s) starts a **5-second countdown** with a **Cancel SOS** dialog/notification action and voice cancel (`"Cancel"`, `"Mat bhejo"`). Dispatches SMS with live GPS location link (plus custom SOS message and optional auto-call) to up to **5 saved emergency contacts**, even while locked, and logs `send_emergency_alert` in **Action Log**.
- **How to Test**:
  1. Enable **Emergency Mode** in **Settings** (grants `SEND_SMS` / `ACCESS_FINE_LOCATION` on-demand) and add up to 5 contacts + custom message.
  2. Say `"Baby help"` — confirm the 5s countdown appears with **Cancel SOS** and cancels cleanly when tapped or spoken; let it finish with real contacts to verify SOS SMS + location link dispatch.

#### 14. Extras: Offline Commands, Personality, Data Saver & Home/Lock Widget (`OfflineCommandEngine`, `BabyWidgetProvider`)
- **Features**: Offline pattern matching works without internet for `flashlight`, `volume`, `brightness`, `alarm`, `DND`, `open app`, and `call contact`. Switch Personality (`Friendly` / `Funny` / `Serious`) and **Data Saver** (compact AI history, no screenshots, shorter replies). Includes a resizable Home & Keyguard Lock-Screen widget (`Mic`, `SOS`, `Stop`, and **last task summary**) plus lock-screen/launcher app shortcut (`res/xml/shortcuts.xml`).
- **How to Test**:
  1. Enable Airplane Mode and say `"Flashlight on karo"`, `"Volume 50%"`, or `"Mummy ko call karo"` — verify instant offline execution.
  2. Toggle **Data Saver** and test **Personality** (`Funny` vs `Serious`) in Settings.
  3. Add the **Baby** widget to the Home/Lock screen (Android launcher widget picker), tap **Mic**, and confirm **Last:** updates after a command.

## Permissions & Room Schema Migrations
- **On-Demand Permissions** (no additions needed in `AndroidManifest.xml` for Phase 3; `SEND_SMS`, `CALL_PHONE`, `ACCESS_FINE_LOCATION`, `USE_BIOMETRIC` are already declared and requested only when each feature is enabled/used):
  - `CAMERA`, `ACCESS_FINE_LOCATION`, `SCHEDULE_EXACT_ALARM`, `READ_CALENDAR`, `SEND_SMS`, `CALL_PHONE`, `USE_BIOMETRIC`.
- **Room Database v1 → v2 (`MIGRATION_1_2`)**: Adds `routines`, `auto_reply_rules`, `memories`, `action_logs`, `reminders`, `macros`, `allowed_apps`, and `emergency_contacts`.
- **Room Database v2 → v3 (`MIGRATION_2_3`)**: Adds `source` to `action_logs` and `createdAt` to `memories`.
- **Room Database v3 → v4 (`MIGRATION_3_4`)**: Adds `saved_places` table for Home/Work/custom geofence coordinates.
- **Phase 3 Schema Note**: No Room schema migration was needed in Phase 3 (database remains at v4); Custom Commands reuse `routines` (`scheduleType = "COMMAND"`) and Macro element identifiers live inside existing `macros.stepsJson`.

