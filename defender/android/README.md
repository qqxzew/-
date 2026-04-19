# MeemawDefender — Android

Background security guardian for elderly users.

## Build

1. Open `android/` folder in Android Studio
2. Copy your keys into `android/local.properties` (already linked to root `local.properties`)
3. Make sure the Node.js dashboard is running: `node server.js` (from project root)
4. Update `SERVER_URL` in `DefenderAnalyzer.kt` / `AlertSender.kt` if your phone is not on the same Wi-Fi as your PC — use your PC's LAN IP instead of `10.0.2.2`

## local.properties keys (project root)

```
OPENAI_API_KEY=sk-...
SENDER_EMAIL=your_gmail@gmail.com
SENDER_APP_PASSWORD=xxxx xxxx xxxx xxxx
```

## Permissions to grant after install

- SMS access (auto-prompted)
- Notification access: Settings → Notifications → Notification access → enable MeemawDefender
- Accessibility: Settings → Accessibility → MeemawDefender → enable
- Display over other apps: Settings → Apps → MeemawDefender → Display over other apps
- Battery: disable battery optimization to keep service alive
