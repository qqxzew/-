# MeemawDefender + MeemawAssist — Полная документация запуска

Проект состоит из **двух Android-приложений** и **веб-дашборда**:

| Компонент | Где работает | Что делает |
|-----------|--------------|-----------|
| **MeemawAssist** (`com.meemaw.assist`) | Android-телефон | AI-ассистент для пожилых. Голос/текст → действие (Wi-Fi, SMS, звонок, блок скама) |
| **MeemawDefender** (`com.meemaw.defender`) | Android-телефон | Фоновый страж. Слушает SMS, уведомления, экран → детектит скам → шлёт алерт |
| **Defender Dashboard** | ПК (Node.js + браузер) | Веб-интерфейс для семьи. Показывает «phone connected», ленту алертов, настройки |

Телефон общается с дашбордом через HTTP. Варианта два: по USB-кабелю (`adb reverse`) или по интернету (`ngrok`).

---

## 1. Требования

### На ПК
- **Node.js** ≥ 18 ([nodejs.org](https://nodejs.org))
- **Android Studio** + Android SDK + Platform-tools (`adb`)
- **JDK 17**
- **ngrok** (для wireless-режима) — [ngrok.com/download](https://ngrok.com/download)
- Windows / macOS / Linux

### На телефоне
- Android 8.0+ (API 26+)
- Включён **Developer mode** + **USB debugging**
- Wi-Fi или мобильный интернет (для wireless-режима)

---

## 2. Структура репозитория

```
HACK V2/
├── app/                        ← MeemawAssist (Android)
│   ├── build.gradle.kts
│   └── src/main/...
├── defender/
│   ├── server.js               ← Express-дашборд (Node.js)
│   ├── package.json
│   ├── public/index.html       ← UI дашборда
│   ├── data/
│   │   ├── config.json         ← familyEmail, grandmaName, active
│   │   └── alerts.json         ← лента алертов (автосоздаётся)
│   └── android/                ← MeemawDefender (Android)
│       ├── build.gradle
│       └── app/src/main/...
├── build.gradle.kts            ← root Gradle для Assist
├── settings.gradle.kts
├── local.properties            ← sdk.dir + OPENAI_API_KEY
└── SETUP.md                    ← этот файл
```

---

## 3. Первоначальная настройка ПК

### 3.1 Клонировать/разархивировать проект

Положить в любую папку. Пример: `C:\Projects\HACK V2`.

### 3.2 Android SDK и `local.properties`

Создай/проверь файл [local.properties](local.properties) в корне:

```properties
sdk.dir=C:\\Users\\<USER>\\AppData\\Local\\Android\\Sdk
OPENAI_API_KEY=sk-proj-...
```

Такой же файл нужен в [defender/android/local.properties](defender/android/local.properties):

```properties
sdk.dir=C:\\Users\\<USER>\\AppData\\Local\\Android\\Sdk
```

На macOS/Linux путь типа `/Users/<USER>/Library/Android/sdk` или `/home/<USER>/Android/Sdk`.

### 3.3 Добавить `adb` в PATH (Windows PowerShell на сессию)

```powershell
$env:Path = 'C:\Users\<USER>\AppData\Local\Android\Sdk\platform-tools;' + $env:Path
adb devices
```

Должен появиться твой телефон со статусом `device`.

---

## 4. Запуск дашборда на ПК

```powershell
cd defender
npm install          # один раз
node server.js
```

Консоль напишет:
```
🛡️  MeemawDefender dashboard running → http://localhost:3000
```

Открой в браузере `http://localhost:3000/`. Увидишь:
- Статус `Phone connected / disconnected`
- Ленту последних 10 алертов
- Поля `Family email`, `Grandma name`, тумблер `Active`

Оставь терминал открытым — сервер должен крутиться, пока ты работаешь.

### Настройки, которые сохраняются
Дашборд пишет их в [defender/data/config.json](defender/data/config.json):
```json
{ "familyEmail": "...", "grandmaName": "Meemaw", "active": true }
```

---

## 5. Сборка и установка Android-приложений

### 5.1 MeemawAssist

Из корня `HACK V2`:

```powershell
.\gradlew.bat :app:installDebug
```

(macOS/Linux: `./gradlew :app:installDebug`)

### 5.2 MeemawDefender

```powershell
cd defender\android
.\gradlew.bat :app:installDebug
cd ..\..
```

Оба приложения появятся на телефоне как **MeemawAssist** и **MeemawDefender**.

---

## 6. Обязательные разрешения на телефоне (Defender)

Без этого Defender не поймает ни SMS, ни email, ни экран. Выполни **один раз** после установки:

```powershell
# 1. NotificationListener — читать уведомления (email, мессенджеры)
adb shell cmd notification allow_listener com.meemaw.defender/com.meemaw.defender.NotificationReceiver

# 2. AccessibilityService — читать текст с экрана
adb shell settings put secure enabled_accessibility_services `
  "com.meemaw.defender/com.meemaw.defender.ScreenMonitor"
adb shell settings put secure accessibility_enabled 1

# 3. Battery whitelist — чтобы Doze не рубил фоновый пинг
adb shell cmd deviceidle whitelist +com.meemaw.defender
adb shell cmd deviceidle whitelist +com.meemaw.assist
```

Руками из настроек телефона то же самое:
1. **Settings → Notifications → Advanced → Device & app notifications / Notification access** → включить *MeemawDefender*
2. **Settings → Accessibility → Installed apps → MeemawDefender** → включить
3. **Settings → Apps → MeemawDefender → Battery → Unrestricted**

Разрешения, которые приложение само попросит при первом запуске: SMS, уведомления, overlay, ignore battery optimizations — **все дать**.

---

## 7. Два режима соединения телефон ↔ дашборд

### Вариант A. По USB-кабелю (быстро, только дома)

```powershell
adb reverse tcp:3000 tcp:3000
```

На телефоне в MeemawDefender → Settings поле **Server URL**:
```
http://127.0.0.1:3000
```
Нажать **Save**, затем **Start Defender**.

Проверка:
```powershell
Invoke-RestMethod http://localhost:3000/api/status
# { "connected": true, "lastPing": <свежий timestamp> }
```

Минус: работает только пока кабель воткнут.

### Вариант B. Через интернет (ngrok, без кабеля)

1. Авторизовать ngrok (один раз):
   ```powershell
   ngrok config add-authtoken <твой токен из dashboard.ngrok.com>
   ```

2. Запустить туннель (отдельный терминал, не закрывать):
   ```powershell
   ngrok http 3000
   ```
   Покажет `Forwarding  https://<random>.ngrok-free.dev -> http://localhost:3000`. Скопировать этот URL.

3. Убрать USB-маршрут если был:
   ```powershell
   adb reverse --remove tcp:3000
   ```

4. На телефоне в MeemawDefender → Settings поле **Server URL** вставить **полный https-URL ngrok**, нажать **Save**.

5. Рестарт сервиса (просто перезайди в приложение или `am force-stop`):
   ```powershell
   adb shell am force-stop com.meemaw.defender
   adb shell am start -n com.meemaw.defender/.MainActivity
   ```

6. Проверка:
   ```powershell
   Invoke-RestMethod http://localhost:3000/api/status
   # connected: true
   (Invoke-RestMethod http://127.0.0.1:4040/api/tunnels).tunnels[0].metrics.conns.count
   # должно расти каждые 15 сек
   ```

⚠️ **Дашборд открывай на `http://localhost:3000/`**, а не через ngrok URL — у ngrok-free есть предупреждающий интерстишл для браузеров (телефон его пропускает, браузер — нет).

⚠️ На free-плане публичный URL меняется при каждом перезапуске `ngrok http 3000`. Тогда надо снова обновить **Server URL** в приложении. Для постоянного URL — ngrok reserved domain или деплой дашборда на VPS.

---

## 8. Проверка полного цикла

### 8.1 Heartbeat
На дашборде должно гореть «Phone connected» (зелёный). Если «disconnected» — смотри раздел 10.

### 8.2 Триггер алерта из SMS
```powershell
adb shell 'am broadcast -n com.meemaw.defender/.TestTriggerReceiver -a com.meemaw.defender.DEMO --es text "Please install anydesk immediately and share the nine digit code to unlock your bank account" --es source sms'
```

Через 2–5 секунд алерт появится:
- На дашборде (в ленте)
- В [defender/data/alerts.json](defender/data/alerts.json)
- Через `Invoke-RestMethod http://localhost:3000/api/alerts`

Должно быть что-то вроде `score=95 danger_type=remote_access`.

### 8.3 Триггер из email / Gmail
Просто пришли с другого адреса письмо с текстом типа *«Install AnyDesk and share the code»*. Как только придёт пуш-уведомление Gmail, `NotificationReceiver` передаст текст в `DefenderService`, и алерт уйдёт на дашборд.

### 8.4 MeemawAssist
Открыть приложение, нажать микрофон, сказать *«Мне звонят из банка и просят установить AnyDesk»* → должен включиться Anti-Scam Guardian (красный экран).

---

## 9. Полный чеклист запуска «с нуля»

```powershell
# 1. Терминал 1: дашборд
cd defender
npm install
node server.js

# 2. Терминал 2: ngrok (только для wireless)
ngrok http 3000
# → скопировать https URL

# 3. Терминал 3: adb / сборка
$env:Path = 'C:\Users\<USER>\AppData\Local\Android\Sdk\platform-tools;' + $env:Path
adb devices

# Первая установка приложений
.\gradlew.bat :app:installDebug
cd defender\android
.\gradlew.bat :app:installDebug
cd ..\..

# Разрешения (раз после установки)
adb shell cmd notification allow_listener com.meemaw.defender/com.meemaw.defender.NotificationReceiver
adb shell settings put secure enabled_accessibility_services "com.meemaw.defender/com.meemaw.defender.ScreenMonitor"
adb shell settings put secure accessibility_enabled 1
adb shell cmd deviceidle whitelist +com.meemaw.defender
adb shell cmd deviceidle whitelist +com.meemaw.assist

# Режим USB
adb reverse tcp:3000 tcp:3000
# → в приложении server url = http://127.0.0.1:3000

# ИЛИ режим wireless
adb reverse --remove tcp:3000
# → в приложении server url = https://<random>.ngrok-free.dev

# Запустить сервисы
adb shell am start -n com.meemaw.defender/.MainActivity
adb shell am start -n com.meemaw.assist/.MainActivity

# Проверка
Invoke-RestMethod http://localhost:3000/api/status
# connected: true
```

---

## 10. Траблшутинг

### «Phone disconnected» на дашборде
Дашборд считает телефон живым, если пинг был не дольше 30 сек назад (`server.js`). Проверь:
- Сервер запущен: `Invoke-RestMethod http://localhost:3000/api/status`
- Defender service крутится: `adb shell dumpsys activity services com.meemaw.defender | findstr ServiceRecord`
- Server URL в приложении верный: `adb shell run-as com.meemaw.defender cat /data/data/com.meemaw.defender/shared_prefs/defender_prefs.xml`
- USB-режим: `adb reverse --list` показывает `tcp:3000 tcp:3000`
- Wireless: ngrok жив (`Invoke-RestMethod http://127.0.0.1:4040/api/tunnels`)
- Battery whitelist (раздел 6, шаг 3)

### Email/SMS не детектится
```powershell
# Listener забинден?
adb shell settings get secure enabled_notification_listeners | findstr meemaw
# Accessibility включён?
adb shell settings get secure enabled_accessibility_services | findstr meemaw
```
Если пусто — повтори раздел 6. Samsung иногда **дропает** accessibility после ребута/апдейта — ставить заново.

### Демо-broadcast не срабатывает
На Android 14+ `am broadcast -a <action>` без пакета система режет. Используй явный компонент:
```powershell
adb shell 'am broadcast -n com.meemaw.defender/.TestTriggerReceiver -a com.meemaw.defender.DEMO --es text "..." --es source sms'
```

### `adb devices` пустой
- Включить **USB debugging** в Developer options
- На телефоне появится диалог «Allow USB debugging?» — **Allow**
- Попробуй сменить USB-режим на «File transfer» или «PTP»
- `adb kill-server; adb start-server`

### ngrok «account required»
Зарегистрируйся на [ngrok.com](https://ngrok.com), скопируй authtoken, выполни `ngrok config add-authtoken <TOKEN>`.

### Сборка Gradle падает
- JDK 17: `java -version`
- `.\gradlew.bat --stop` затем снова
- Удалить `.gradle/` и `build/` и пересобрать

### Защита от повторных алертов
Defender хранит хеш последнего алерта в `defender_prefs.xml` (`last_alert_hash`). Если триггерится один и тот же текст — сначала пропадает из ленты. Для теста меняй текст или сноси хеш:
```powershell
adb shell run-as com.meemaw.defender rm /data/data/com.meemaw.defender/shared_prefs/defender_prefs.xml
adb shell am force-stop com.meemaw.defender
```

---

## 11. API дашборда (шпаргалка)

| Method | Path | Кто зовёт | Назначение |
|--------|------|-----------|-----------|
| `GET` | `/api/ping` | Android (каждые 15с) | Heartbeat |
| `GET` | `/api/status` | Dashboard UI | `{connected, lastPing}` |
| `POST` | `/api/alert` | Android | Пуш алерта `{score, danger_type, explanation, original}` |
| `GET` | `/api/alerts` | Dashboard UI | Последние 10 алертов |
| `GET` | `/api/settings` | Android/UI | `{familyEmail, grandmaName, active}` |
| `POST` | `/api/settings` | Dashboard UI | Сохранить настройки |
| `GET` | `/api/family-email` | Android | Куда слать email-алерт |

---

## 12. Где что лежит (ключевые файлы)

- Дашборд сервер: [defender/server.js](defender/server.js)
- Дашборд UI: [defender/public/index.html](defender/public/index.html)
- Defender Android manifest: [defender/android/app/src/main/AndroidManifest.xml](defender/android/app/src/main/AndroidManifest.xml)
- Foreground service: [defender/android/app/src/main/java/com/meemaw/defender/DefenderService.kt](defender/android/app/src/main/java/com/meemaw/defender/DefenderService.kt)
- Notification listener: [defender/android/app/src/main/java/com/meemaw/defender/NotificationReceiver.kt](defender/android/app/src/main/java/com/meemaw/defender/NotificationReceiver.kt)
- Screen accessibility: [defender/android/app/src/main/java/com/meemaw/defender/ScreenMonitor.kt](defender/android/app/src/main/java/com/meemaw/defender/ScreenMonitor.kt)
- SMS receiver: [defender/android/app/src/main/java/com/meemaw/defender/SmsReceiver.kt](defender/android/app/src/main/java/com/meemaw/defender/SmsReceiver.kt)
- Alert sender: [defender/android/app/src/main/java/com/meemaw/defender/AlertSender.kt](defender/android/app/src/main/java/com/meemaw/defender/AlertSender.kt)
- Assist root: [app/src/main/java/com/meemaw/assist/](app/src/main/java/com/meemaw/assist/)
- Общий readme по Assist: [README.md](README.md)

---

## 13. TL;DR команды на каждый день

```powershell
# Утром
cd defender; node server.js          # терминал 1
ngrok http 3000                      # терминал 2 (если без кабеля)
adb shell am start -n com.meemaw.defender/.MainActivity

# Проверить
Invoke-RestMethod http://localhost:3000/api/status

# Если сломалось → раздел 10
```

Всё. Готово к демо.
