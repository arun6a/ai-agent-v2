# AI Agent v2

Complete UI/UX rebuild of [ai-agent-apk](https://github.com/arun6a/ai-agent-apk) using Jetpack Compose.

## Status
🚧 **In development** — v2.0.0 build 1 (scaffold)

## Design
- **Direction:** Friendly AI Assistant + Power-user Dense
- **Accent:** Emerald `#10B981` (warm + smart, not the typical indigo/blue)
- **Default theme:** Light (`#FAFAF9` warm off-white)
- **Dark theme:** Deep slate (`#0F0F12`)
- **Typography:** Inter (sans), JetBrains Mono (mono)
- **App icon:** Concept 1 — Emerald Orb (hexagonal AI brain on dark)

## Tech Stack
- Kotlin + Jetpack Compose (Material 3)
- Min SDK 24, Target SDK 34
- Compose BOM 2024.09.02
- Navigation Compose 2.8.1
- DataStore (modern SharedPreferences replacement)
- ML Kit (on-device OCR)
- WorkManager (background triggers)
- NanoHTTPD (in-app HTTP server)
- OkHttp (LLM API calls)

## Architecture
```
app/src/main/java/com/ai/agent/
├── MainActivity.kt          ← Compose entry point
├── ui/
│   ├── theme/               ← Color, Type, Shape, Theme
│   ├── components/          ← Reusable composables (CustomColors, etc.)
│   ├── screens/             ← ChatScreen, HubScreen, BrowserScreen, ...
│   └── navigation/          ← NavHost, routes
├── llm/                     ← LLM client + 13 providers (carry-over from v1)
├── tools/                   ← 100+ tools (carry-over)
├── accessibility/            ← Accessibility service (carry-over)
├── browser/                 ← Browser controller (carry-over)
├── ml/                      ← ML Kit OCR helper (carry-over)
├── rules/                   ← 36 trigger receivers (carry-over)
├── service/                 ← Agent service + HTTP server + overlay (carry-over)
├── skills/                  ← Skill manager (carry-over)
└── storage/                 ← Agent database (carry-over)
```

## Carry-over from v1 (battle-tested)
These were preserved byte-for-byte from v6.4.4:
- `LLMClient.kt` — 13 providers, native function calling + manual JSON fallback
- `AIProvider.kt` — all 15 Z.ai models + Groq + Gemini + OpenRouter + ...
- `ToolExecutor.kt` — 100+ tools across 18 categories
- `AgentAccessibilityService.kt` — screen control (tap, type, swipe, readScreen, ...)
- `AgentDatabase.kt` — memory + knowledge base (Jaccard similarity)
- `DynamicPromptBuilder.kt` — 5 prompt modes (smart/dynamic/balanced/fast/custom)
- All 36 rule receivers (battery, app open, calendar, USB, dock, etc.)
- All 20 built-in skills (Markdown files)
- All 8 plugin definitions

## Rebuilt from scratch (the focus)
- All UI screens (Compose, fresh design)
- Navigation flow
- Design system (colors, typography, components)
- App icon + splash screen
- Hub layout (cleaner organization)

## Build

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Design Mockups
HTML mockups were built first and approved before translation to Compose:
- [Design System](https://github.com/arun6a/ai-agent-v2/blob/main/design/01-design-system.html)
- [Chat Screen](https://github.com/arun6a/ai-agent-v2/blob/main/design/02-chat-screen.html)
- [Hub Screen](https://github.com/arun6a/ai-agent-v2/blob/main/design/03-hub-screen.html)
- [App Icons](https://github.com/arun6a/ai-agent-v2/blob/main/design/04-app-icons.html)

## Versioning
- v1.x → [ai-agent-apk](https://github.com/arun6a/ai-agent-apk) (XML layouts, battle-tested, fallback)
- v2.x → this repo (Compose, fresh design, in development)

If v2 fails on device, fall back to v1 (v6.4.4).

## License
Personal project — not for distribution.
