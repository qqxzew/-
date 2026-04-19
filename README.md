# MeemawAssist 🤖💙

**Autonomous AI tech-support assistant for elderly users.**

The user describes a problem by voice or text — the app figures out what needs to be done and acts on it automatically.

---

## Operating Modes

The AI picks a mode automatically (priority top to bottom):

| Mode | What it does | Example |
|------|-------------|---------|
| **🛡 Anti-Scam Guardian** | Blocks all actions, shows a scam warning overlay | *"Someone from the bank called and asked me to install AnyDesk"* |
| **🔧 Phone Agent** | Silently performs actions via system APIs (Wi-Fi, Bluetooth, volume, brightness) | *"I have no sound"* → automatically turns volume up |
| **✉️ Compose & Route** | Opens the right app with a pre-filled message | *"Text Ivan on Telegram"* → opens Telegram compose |
| **💬 Chat & Advice** | Answers general questions as a friendly helper | *"What's the weather?"*, *"How do I boil rice?"* |

---

## Tech Stack

- **Kotlin** · Min SDK 26 · Target SDK 34
- **OpenAI GPT-4o-mini** — reasoning engine
- **Retrofit2 + OkHttp + Gson** — networking
- **AccessibilityService** — screen interaction (tap, swipe, type)
- **Kotlin Coroutines** — async
- **MVVM** — ViewModel + StateFlow
- **Material Design 3** — UI

---

## Project Structure

```
app/src/main/java/com/meemaw/assist/
├── MainActivity.kt                  # Chat UI, voice input, status indicators
├── MainViewModel.kt                 # Mode routing, StateFlow
├── data/
│   ├── LLMRepository.kt            # OpenAI API calls, JSON response parsing
│   └── api/
│       ├── Models.kt                # Request/response data classes
│       └── OpenAIService.kt         # Retrofit interface
├── prompt/
│   └── PromptBuilder.kt            # System prompt + JSON schema for AI
├── agent/
│   ├── AgentLoop.kt                # Multi-step command execution
│   ├── ScreenReader.kt             # Read UI elements via AccessibilityService
│   ├── ScreenActions.kt            # Tap, swipe, type (gestures)
│   └── SystemConfigExecutor.kt     # Wi-Fi, Bluetooth, volume, brightness, Settings
├── accessibility/
│   └── MeemawAccessibilityService.kt  # AccessibilityEvent handling
└── ui/
    ├── ChatAdapter.kt              # RecyclerView adapter (user/ai/scam bubbles)
    └── MessageItem.kt              # Sealed class for message types
```

---

## Available Agent Commands

| Command | Description |
|---------|-------------|
| `wifi_on` / `wifi_off` | Toggle Wi-Fi |
| `bluetooth_on` / `bluetooth_off` | Toggle Bluetooth |
| `volume_up` / `volume_down` / `volume_max` / `volume_mute` | Volume control |
| `brightness_up` / `brightness_down` / `brightness_max` | Brightness control |
| `open_settings` | Open the relevant settings screen |
| `restart_suggestion` | Suggest a reboot |

---

## Compose Mode: Supported Apps

Telegram · WhatsApp · SMS · Gmail · Phone (call)

---

## UI Design

- Font **≥ 18sp** — large, readable
- Accent color **#00A8E0** (AT&T Blue)
- Chat bubbles: user on the right (blue), AI on the left (grey)
- Large microphone button for voice input
- Status overlays: *"Listening…"* · *"Thinking…"* · *"Fixing…"*
- Red border for scam warnings
- High contrast for visually impaired users

---

## Permissions

```
INTERNET, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE,
BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_CONNECT,
MODIFY_AUDIO_SETTINGS, RECORD_AUDIO, WRITE_SETTINGS,
BIND_ACCESSIBILITY_SERVICE
```

---

## Setup

1. Add your OpenAI key to `local.properties`:
   ```
   OPENAI_API_KEY=sk-proj-...
   ```
2. Open the project in Android Studio
3. Sync Gradle → Run
4. On the device: **Settings → Accessibility → MeemawAssist** → enable

---

## 🛡️ MeemawDefender

**Background scam-protection service** — monitors SMS, notifications, and on-screen text in real time using GPT-4o-mini.

### How It Works

| Source | Trigger |
|--------|---------|
| **ScreenMonitor** | AccessibilityService reads on-screen text every 4 seconds |
| **NotificationReceiver** | Intercepts all incoming notifications (SMS, Gmail, messengers) |
| **SmsReceiver** | BroadcastReceiver on incoming SMS |

When a threat is detected — shows a full-screen red block and sends an alert to the dashboard.

### Dashboard (Node.js + MongoDB Atlas)

```
cd defender
node server.js   # http://localhost:3000
```

For external access — ngrok:
```
ngrok http 3000
```

### MongoDB Atlas Integration

All alerts are stored in **MongoDB Atlas** (cloud):

| Feature | Description |
|---------|-------------|
| **TTL Index** | Alerts auto-deleted after 30 days |
| **Aggregation Pipeline** | `GET /api/analytics` — threat stats by type, avg/max score |
| **Full-text Search** | `GET /api/search?q=anydesk` — search across all alerts |
| **Upsert Config** | Settings (email, name) stored as a singleton document |

Configure `.env` in the `defender/` folder:
```
MONGODB_URI=mongodb+srv://user:password@cluster.mongodb.net/meemawdefender
```

### API Endpoints

| Method | URL | Description |
|--------|-----|-------------|
| `GET` | `/api/ping` | Heartbeat from the Android app |
| `GET` | `/api/status` | Connection status (30s timeout) |
| `POST` | `/api/alert` | New alert from the phone |
| `GET` | `/api/alerts` | Last 10 alerts |
| `GET` | `/api/analytics` | Threat type statistics (Atlas Aggregation) |
| `GET` | `/api/search?q=` | Search alerts |
| `GET/POST` | `/api/settings` | Settings (email, name, active flag) |

### Android → Dashboard via USB

```powershell
adb reverse tcp:3000 tcp:3000
adb shell am start -n com.meemaw.defender/.MainActivity
# In the app: Server URL = http://127.0.0.1:3000 → Save
```

### Test Without SMS

```powershell
adb shell "am broadcast -n com.meemaw.defender/.TestTriggerReceiver \
  -a com.meemaw.defender.DEMO \
  --es text 'install anydesk and share the nine digit code' \
  --es source sms"
```

---

## 🧩 Meemaw Assist — Chrome Extension (MV3)

A React + Vite Chrome extension that helps elderly users complete tasks in the browser. It takes a screenshot of the active tab, asks **Google Gemini** to identify the single next small step, draws a large numbered red arrow on the target element, and reads the instruction aloud via **ElevenLabs**.

Folder: [exe/meemaw-assist](exe/meemaw-assist)

### What It Does

- **Screenshot → next step.** The service worker captures the visible tab and sends the PNG to Gemini. Gemini returns exactly ONE action (click / type / choose) with a bounding box, target text, and role.
- **Snap-to-DOM arrow.** The content script snaps the arrow to the real DOM element using the target text + coordinates.
- **Voice.** The instruction is spoken via ElevenLabs (with an in-memory cache).
- **Diagnose mode.** When there's nothing on screen to analyze — a gentle one-question-at-a-time chat flow with tap-to-reply buttons.
- **Multilingual:** EN / SK / DE.
- **Accessibility:** large text, high contrast, light/dark theme, voice input.

### Tech Stack

- **React 19** + **Vite 8** + **TailwindCSS 4**
- **Manifest V3** (popup, module service worker, content script on `<all_urls>`)
- **Google Gemini** — vision + reasoning
- **ElevenLabs** — TTS
- **Web Speech API** — voice input
- **chrome.storage.sync** — settings

### Setup

1. API keys go in `.env` in the `exe/` folder:
   ```env
   VITE_GEMINI_API_KEY=AIza...
   VITE_ELEVENLABS_API_KEY=sk_...
   VITE_ELEVENLABS_VOICE_ID=JBFqnCBsd6RMkjVDRZzb
   ```
   Get a Gemini key at <https://aistudio.google.com/apikey>. If the key is missing, mock mode activates and the badge shows `Mock · <reason>`.

2. Build:
   ```bash
   cd exe/meemaw-assist
   npm install
   npm run build
   ```

3. Load into Chrome:
   - Open `chrome://extensions`
   - Enable **Developer mode**
   - **Load unpacked** → select `exe/meemaw-assist/dist`
   - Pin the Meemaw icon to the toolbar

4. Use it:
   - Open any normal website (e.g. gmail.com)
   - Click the Meemaw icon
   - Type or speak the goal ("send an email to my daughter")
   - Press the big green button — an arrow and spoken instruction appear on the page
   - Press **Done** after completing the step — the extension screenshots again and shows the next one

Badge at the top of the guide: **AI · Gemini** — real model in use; **Mock · <reason>** — fallback.

### Dev Mode

```bash
npm run watch
```
Vite rebuilds `dist/` on every change. After each change, hit the reload icon on the Meemaw card in `chrome://extensions`.

### File Structure

| File | Role |
|------|------|
| `public/manifest.json` | MV3 manifest |
| `public/steps.json` | Optional pre-baked scenarios for scripted flows |
| `src/background.js` | Service worker: tab capture, Gemini calls, TTS prefetch, session state |
| `src/content.js` | Draws the numbered arrow and plays audio on the page |
| `src/precision-engine.js` | Snaps Gemini's target text / bbox to a real DOM element |
| `src/services/openaiService.js` | Gemini vision client (legacy name) |
| `src/services/diagnoseService.js` | Gemini text chat — diagnose mode |
| `src/services/ttsService.js` | ElevenLabs TTS with in-memory cache |
| `src/Popup.jsx` | React popup UI (home / guide / done views) |
| `src/components/VoiceInput.jsx` | Mic button + Web Speech API recognition |
| `src/components/StepPreview.jsx` | Annotated screenshot preview |
| `src/components/SettingsPanel.jsx` | Language, text size, high contrast, voice |
| `src/settings.js` | `chrome.storage.sync` wrapper |
| `src/i18n.js` | EN / SK / DE strings |

### Permissions

`activeTab`, `tabs`, `scripting`, `storage`, `host_permissions: <all_urls>` — the minimum needed to read the active tab URL, call `chrome.tabs.captureVisibleTab`, and inject the overlay content script.

### Privacy

- Screenshots are sent directly from the service worker to `https://generativelanguage.googleapis.com` (Gemini).
- Instruction text is sent to `https://api.elevenlabs.io` for speech.
- No backend, no analytics, no third-party hops.
- Keys are inlined into the build at compile time. Don't publish to the Chrome Web Store without moving keys behind a backend proxy.

### Troubleshooting

| Symptom | Likely cause |
|---------|-------------|
| Badge `Mock · no_api_key` | `VITE_GEMINI_API_KEY` missing or doesn't start with `AIza` |
| Badge `Mock · http_400 / 403` | Gemini key invalid or quota exhausted |
| Arrow points to the wrong place | `precision-engine.js` couldn't match `target_text` — reload the page or rephrase the goal |
| "This page can't be assisted" | You're on `chrome://`, Chrome Web Store, or a PDF — switch to a regular site |
| No voice | `VITE_ELEVENLABS_API_KEY` missing or voice disabled in Settings |

> ⚠️ The popup **cannot capture your screen** when opened via `npm run dev` — `chrome.tabs.captureVisibleTab` only works for an installed extension. Always load the built `dist/`.
