# MeemawAssist — Project Overview

An AI-powered support system for elderly users. Three components work together.

---

## Inspiration

We wanted to explore new technologies — mobile development, Kotlin, AI APIs — and challenge ourselves with something outside our comfort zone. As we started thinking about who actually struggles the most with technology, the answer was obvious: our grandparents. My grandmother wouldn't know what to do if she got a scam text, or if the Wi-Fi just stopped working, or if she needed to send a photo on WhatsApp. We wanted to build something that she could genuinely use — no manual, no learning curve, just talk to it and it figures the rest out.

## What it does

MeemawAssist is a three-part AI system built for elderly users:

- Android app (MeemawAssist) — a voice-first assistant the user talks to. It picks one of four modes automatically: block a scam, fix a phone setting, compose and send a message, or just answer a question. It uses Android's Accessibility API to interact with the phone directly on behalf of the user.
- Android background service (MeemawDefender) — runs silently 24/7 and monitors every SMS, notification, and word on screen for scam patterns. When it detects a threat, it covers the screen with a red warning and logs the alert to a cloud dashboard that a family member can check.
- Chrome Extension (Meemaw Assist) — for when the user is on a computer. It takes a screenshot of the current webpage, asks an AI what the single next step is, and draws a big red arrow on that exact element while reading the instruction aloud. Works step by step until the task is done.

## How we built it

The Android app is built in Kotlin with MVVM architecture, using OpenAI GPT-4o-mini as the reasoning engine via Retrofit. The Accessibility Service layer handles reading the screen and performing gestures. MeemawDefender runs as a persistent background service with a BroadcastReceiver for SMS and notifications, and a Node.js + MongoDB Atlas dashboard for alert storage and analytics. The Chrome extension is a Manifest V3 extension built with React + Vite, using Google Gemini for vision-based step reasoning and ElevenLabs for spoken instructions.

## Challenges we ran into

Android permissions and system control were the hardest part. Getting the Accessibility Service to reliably read and interact with third-party apps, managing runtime permission flows, and working around Android's background process restrictions all took far more time than expected. Every version of Android behaves slightly differently, and the emulator doesn't always match real device behavior. We also had to figure out how to keep MeemawDefender alive in the background without draining the battery.

## Accomplishments that we're proud of

The Defender mode turned out way better than we expected. We honestly weren't sure it would work — reading every notification, every SMS, and scanning on-screen text every few seconds, then running it through an AI to classify threats in real time — and somehow it actually works. Seeing a scam SMS trigger the red warning screen in under a second felt like magic the first time it fired correctly.

## What we learned

We learned Kotlin and Android development from scratch during this hackathon. We picked up a lot about how Android's permission and accessibility systems work under the hood. Beyond the technical side — we learned that sleep is not optional, met a lot of great people, and proved to ourselves that you can build something genuinely useful in a very short time if you stay focused.

## What's next for MeemawAssist

We want to keep improving all three components and eventually ship it as a real product. The immediate next steps are: a proper onboarding flow so any elderly person can set it up themselves, a caregiver mobile app (instead of just a web dashboard), and expanding language support. Long-term, we'd love to get it into the hands of real families and iterate based on how grandparents actually use it.

---

---

## 📱 Android App — MeemawAssist

An Android app the user talks to. You describe a problem out loud or type it — the AI decides what to do and does it.

Four things it can do:

- Detect a scam — if the message sounds like a fraud attempt ("someone called from the bank"), the app locks the screen and shows a red warning.
- Fix the phone — if you say "I have no sound" or "turn on Wi-Fi", the app silently does it without asking anything.
- Send a message — if you say "text my daughter on WhatsApp", it opens WhatsApp with the message already written.
- Answer questions — for everything else (weather, recipes, general advice), it just chats.

The AI reads what's on screen using Android's Accessibility API, so it can tap buttons and fill in fields on your behalf.

---

## 🛡️ MeemawDefender

A background Android service that watches for scams 24/7 without the user needing to open anything.

What it monitors:
- Every incoming SMS
- Every app notification (Gmail, Telegram, WhatsApp, etc.)
- Text visible on screen — checked every few seconds

When it spots a threat, it covers the screen with a red alert and logs it to a dashboard.

Dashboard — a small web server (Node.js) connected to a cloud database. A caregiver or family member can open it in a browser and see a live feed of all alerts from the phone: what was detected, when, and how dangerous it was. The dashboard also shows analytics — which types of threats appear most often.

---

## 🧩 Chrome Extension — Meemaw Assist

A browser extension for when the elderly user is on a computer, not a phone.

How it works:

1. The user clicks the Meemaw icon and says or types what they want to do ("send an email to my daughter").
2. The extension takes a screenshot of the current webpage.
3. The AI looks at the screenshot and figures out the single next small step.
4. A big red numbered arrow appears on the page pointing exactly at the button or field to interact with.
5. A calm voice reads the instruction out loud.
6. The user does the step, clicks Done, and the extension moves to the next one.

If there's no webpage involved ("my printer isn't working"), it switches to a gentle chat mode that asks one question at a time with simple tap-to-answer buttons.

Works on any normal website. Supports English, Slovak, and German. Has large text, high-contrast mode, and voice input for accessibility.

---

## How the pieces fit together

```
User speaks / types
        ↓
  Android App (MeemawAssist)
  ├── AI decides mode
  ├── Fixes phone settings directly
  ├── Opens apps and composes messages
  └── Warns about scams → red screen

  Android Background (MeemawDefender)
  ├── Watches SMS / notifications / screen text
  └── Logs threats → Dashboard (browser, cloud)

  Chrome Extension (Meemaw Assist)
  ├── Screenshots active browser tab
  ├── AI finds next step → red arrow on page
  └── Voice instruction read aloud
```

All three use AI models to understand natural language and visual context. No technical knowledge required from the user.
