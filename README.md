# MeemawAssist 🤖💙

**Автономный AI-ассистент технической поддержки для пожилых пользователей.**

Пользователь описывает проблему голосом или текстом — приложение само определяет, что нужно сделать, и действует.

---

## Режимы работы

AI автоматически выбирает режим (приоритет сверху вниз):

| Режим | Что делает | Пример |
|-------|-----------|--------|
| **🛡 Anti-Scam Guardian** | Блокирует все действия, показывает предупреждение о мошенничестве | *«Мне позвонили из банка и просят установить AnyDesk»* |
| **🔧 Phone Agent** | Молча выполняет действия через системные API (Wi-Fi, Bluetooth, громкость, яркость) | *«У меня нет звука»* → автоматически выкручивает громкость |
| **✉️ Compose & Route** | Открывает нужное приложение с предзаполненным сообщением | *«Напиши Ивану в Телеграм»* → открывает Telegram |
| **💬 Chat & Advice** | Отвечает на общие вопросы как дружелюбный помощник | *«Какая погода?»*, *«Как сварить рис?»* |

---

## Стек технологий

- **Kotlin** · Min SDK 26 · Target SDK 34
- **OpenAI GPT-4o-mini** — reasoning engine (ключ в `local.properties`)
- **Retrofit2 + OkHttp + Gson** — сетевой слой
- **AccessibilityService** — взаимодействие с экраном (тап, свайп, ввод текста)
- **Kotlin Coroutines** — асинхронность
- **MVVM** — ViewModel + StateFlow
- **Material Design 3** — UI

---

## Структура проекта

```
app/src/main/java/com/meemaw/assist/
├── MainActivity.kt                  # UI чата, голосовой ввод, статус-индикаторы
├── MainViewModel.kt                 # Маршрутизация по режимам, StateFlow
├── data/
│   ├── LLMRepository.kt            # Вызовы OpenAI API, парсинг JSON-ответа
│   └── api/
│       ├── Models.kt                # Data-классы запросов/ответов
│       └── OpenAIService.kt         # Retrofit-интерфейс
├── prompt/
│   └── PromptBuilder.kt            # Системный промпт + JSON-схема для AI
├── agent/
│   ├── AgentLoop.kt                # Многошаговое выполнение команд
│   ├── ScreenReader.kt             # Чтение UI-элементов через AccessibilityService
│   ├── ScreenActions.kt            # Тап, свайп, ввод текста (жесты)
│   └── SystemConfigExecutor.kt     # Wi-Fi, Bluetooth, громкость, яркость, Settings
├── accessibility/
│   └── MeemawAccessibilityService.kt  # Обработка AccessibilityEvent
└── ui/
    ├── ChatAdapter.kt              # RecyclerView адаптер (user/ai/scam bubbles)
    └── MessageItem.kt              # Sealed class сообщений
```

---

## Доступные действия Agent-режима

| Команда | Описание |
|---------|----------|
| `wifi_on` / `wifi_off` | Включить / выключить Wi-Fi |
| `bluetooth_on` / `bluetooth_off` | Включить / выключить Bluetooth |
| `volume_up` / `volume_down` / `volume_max` / `volume_mute` | Управление громкостью |
| `brightness_up` / `brightness_down` / `brightness_max` | Управление яркостью |
| `open_settings` | Открыть нужный раздел настроек |
| `restart_suggestion` | Предложить перезагрузку |

---

## Compose-режим: поддерживаемые приложения

Telegram · WhatsApp · SMS · Gmail (Email) · Телефон (звонок)

---

## UI-дизайн

- Шрифт **≥ 18sp** — крупный, читаемый
- Акцентный цвет **#00A8E0** (AT&T Blue)
- Пузыри чата: пользователь справа (синий), AI слева (серый)
- Большая кнопка микрофона для голосового ввода
- Статус-оверлеи: *«Listening…»* · *«Thinking…»* · *«Fixing…»*
- Красная рамка для предупреждений о мошенничестве
- Высокий контраст для слабовидящих

---

## Разрешения

```
INTERNET, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE,
BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_CONNECT,
MODIFY_AUDIO_SETTINGS, RECORD_AUDIO, WRITE_SETTINGS,
BIND_ACCESSIBILITY_SERVICE
```

---

## Настройка

1. Вставить ключ OpenAI в `local.properties`:
   ```
   OPENAI_API_KEY=sk-proj-...
   ```
2. Открыть проект в Android Studio
3. Sync Gradle → Run
4. На устройстве: **Настройки → Специальные возможности → MeemawAssist** — включить

---

## 🛡️ MeemawDefender

**Фоновый сервис защиты от мошенников** — анализирует SMS, уведомления и текст на экране в реальном времени с помощью GPT-4o-mini.

### Как работает

| Источник | Триггер |
|----------|---------|
| **ScreenMonitor** | AccessibilityService читает текст на экране каждые 4 секунды |
| **NotificationReceiver** | Перехватывает все входящие уведомления (SMS, Gmail, мессенджеры) |
| **SmsReceiver** | BroadcastReceiver на входящие SMS |

При обнаружении угрозы — показывает красный экран-блокировку и отправляет алерт на дашборд.

### Dashboard (Node.js + MongoDB Atlas)

```
cd defender
node server.js   # http://localhost:3000
```

Для доступа извне — ngrok:
```
ngrok http 3000
```

### MongoDB Atlas интеграция

Все алерты хранятся в **MongoDB Atlas** (облако):

| Функция | Описание |
|---------|----------|
| **TTL Index** | Алерты автоматически удаляются через 30 дней |
| **Aggregation Pipeline** | `GET /api/analytics` — статистика угроз по типу, avg/max score |
| **Full-text Search** | `GET /api/search?q=anydesk` — поиск по всем алертам |
| **Upsert Config** | Настройки (email, имя) хранятся как singleton-документ |

Настройка `.env` в папке `defender/`:
```
MONGODB_URI=mongodb+srv://user:password@cluster0.xxxxx.mongodb.net/meemawdefender
```

### API эндпоинты

| Метод | URL | Описание |
|-------|-----|----------|
| `GET` | `/api/ping` | Heartbeat от Android-приложения |
| `GET` | `/api/status` | Статус подключения (30s таймаут) |
| `POST` | `/api/alert` | Новый алерт с телефона |
| `GET` | `/api/alerts` | Последние 10 алертов |
| `GET` | `/api/analytics` | Статистика по типам угроз (Atlas Aggregation) |
| `GET` | `/api/search?q=` | Поиск по алертам |
| `GET/POST` | `/api/settings` | Настройки (email, имя, активность) |

### Настройка Android → Dashboard (USB)

```powershell
adb reverse tcp:3000 tcp:3000
adb shell am start -n com.meemaw.defender/.MainActivity
# В приложении: Server URL = http://127.0.0.1:3000 → Save
```

### Тест без SMS

```powershell
adb shell "am broadcast -n com.meemaw.defender/.TestTriggerReceiver \
  -a com.meemaw.defender.DEMO \
  --es text 'install anydesk and share the nine digit code' \
  --es source sms"
```
