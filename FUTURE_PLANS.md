# Master Future Plans — AI Agent APK

**Date**: 2026-10-11
**Merged by**: Sandbox-2 (combining Sandbox-1 + Sandbox-2 visions)
**Current version**: v6.3.2

---

## What's Been Done (v1.0 → v6.3.2)

- ✅ 100+ tools (18 categories)
- ✅ 36 trigger types
- ✅ 20 skills + user-created via chat
- ✅ 10 plugins
- ✅ 12 AI providers
- ✅ 5 prompt modes (Smart/Dynamic/Balanced/Fast/Custom)
- ✅ Hub with 13 sections
- ✅ File attachments + knowledge store
- ✅ Strategy switching + always learn
- ✅ HTTP server for remote control
- ✅ Browser ↔ Chat quick switch
- ✅ Google Voice input
- ✅ Overlay fix (no duplicates)
- ✅ App folders on install
- ✅ Skill management (create/delete/get)
- ✅ Direct web search (no proxy needed)

---

## v6.4.0 — Bug Fixes (NEXT)

| # | Feature | Priority | From |
|---|---|---|---|
| 1 | Screenshot MIUI fix (PixelCopy/MediaProjection) | 🔴 Critical | Both |
| 2 | Timeout system (30s/step, 3min/task) | 🔴 High | Both |
| 3 | Browser stays foreground (all steps visible) | 🟡 Medium | Sandbox-1 |
| 4 | Ask user for help (after 3 failures) | 🟡 Medium | Sandbox-1 |
| 5 | Stop button polish | 🟢 Low | Both |
| 6 | Empty catch blocks logging | 🟢 Low | Sandbox-1 |

---

## v7.0.0 — Reliability + Trust

| # | Feature | From |
|---|---|---|
| 7 | AI Confidence Score (show %, ask if <70%) | Sandbox-1 |
| 8 | Undo Button (every action has undo) | Sandbox-1 |
| 9 | Skill Recording (user does once → AI records → 1 call next time) | Sandbox-1 |
| 10 | WhatsApp auto-send (tap send button automatically) | Sandbox-1 |
| 11 | Timeout: graceful stop + tell user what failed | Both |
| 12 | Better error messages (distinguish "no text" from "screenshot broken") | Both |

---

## v7.5.0 — Speed + Connectivity

| # | Feature | From |
|---|---|---|
| 13 | Cache common results (battery, time — don't re-fetch) | Sandbox-1 |
| 14 | Predictive actions (AI learns user's routine) | Sandbox-1 |
| 15 | Local LLM for simple tasks (no API call needed) | Both |
| 16 | Background pre-loading (AI predicts next step) | Sandbox-1 |
| 17 | Telegram bot (control phone from laptop/anywhere) | Both |
| 18 | AI Diary (nightly summary: what I did, calls used, failures) | Sandbox-1 |

---

## v8.0.0 — Proactive AI + Developer Agent

| # | Feature | From |
|---|---|---|
| 19 | Proactive AI (7am → morning briefing, battery low → suggest) | Sandbox-1 |
| 20 | Context awareness (location, time, calendar, recent apps) | Sandbox-1 |
| 21 | Code execution (write Python/JS → run on-device via Termux) | Sandbox-2 |
| 22 | Code generation ("write a script that logs battery hourly") | Sandbox-2 |
| 23 | GitHub integration (push code, create PRs, manage issues) | Sandbox-2 |
| 24 | Debug mode (AI reads own logcat, identifies bugs) | Sandbox-2 |
| 25 | Skill compiler (AI writes skills from natural language) | Sandbox-2 |
| 26 | Auto-testing (AI tests own tools + reports failures) | Sandbox-2 |
| 27 | APK self-modify (AI can modify code, rebuild, install) | Sandbox-2 |

---

## v8.5.0 — Image Editing + Generation

| # | Feature | From |
|---|---|---|
| 28 | Image editing (crop, resize, rotate, filters via ffmpeg) | Sandbox-2 |
| 29 | Image generation (Stable Diffusion on-device or cloud API) | Sandbox-2 |
| 30 | Screenshot annotation (arrows, highlights, text) | Sandbox-2 |
| 31 | Photo enhancement (auto brightness, contrast, noise reduction) | Sandbox-2 |
| 32 | Batch processing ("resize all photos to 1080p") | Sandbox-2 |
| 33 | Meme generator (templates + user text) | Sandbox-2 |
| 34 | QR code generator + scanner | Sandbox-2 |
| 35 | Collage maker (combine multiple photos) | Sandbox-2 |

---

## v9.0.0 — Video Editing + Generation + Personal AI

| # | Feature | From |
|---|---|---|
| 36 | Video editing (trim, merge, add music, subtitles) | Sandbox-2 |
| 37 | Text-to-video ("create 10s video of waves") | Sandbox-2 |
| 38 | Photo-to-video (animate static photos, ken burns) | Sandbox-2 |
| 39 | Slideshow video (photos + music) | Sandbox-2 |
| 40 | GIF maker (create animated GIFs) | Sandbox-2 |
| 41 | Screen recording (AI narrates while recording) | Sandbox-2 |
| 42 | Video transcription (audio to text) | Sandbox-2 |
| 43 | Video translation (subtitle translation) | Sandbox-2 |
| 44 | Video compression (reduce file size) | Sandbox-2 |
| 45 | Learn user schedule (calendar + usage patterns) | Sandbox-1 |
| 46 | Learn contacts (who is Mom, who is boss) | Sandbox-1 |
| 47 | Learn preferences (always opens YouTube at 8pm) | Sandbox-1 |
| 48 | Learn apps (which apps you use most) | Sandbox-1 |
| 49 | Voice ID (only responds to YOUR voice) | Sandbox-1 |

---

## v9.5.0 — Advanced AI + Voice

| # | Feature | From |
|---|---|---|
| 50 | Anthropic prompt caching (90% token savings) | Sandbox-2 |
| 51 | Multi-model routing (Groq simple, Claude complex) | Sandbox-2 |
| 52 | Streaming responses (real-time, like ChatGPT) | Both |
| 53 | Continuous voice conversation (push-to-talk → reply → listen) | Both |
| 54 | Multi-turn context (full conversation memory) | Both |
| 55 | AI Voice Personality (Friendly/Professional/Funny) | Sandbox-1 |
| 56 | Custom wake word ("Hey Arun") | Sandbox-1 |
| 57 | Voice emotions (happy when success, concerned when stuck) | Sandbox-1 |
| 58 | Whisper STT (high-accuracy speech recognition) | Sandbox-2 |
| 59 | ElevenLabs TTS (natural voice cloning) | Sandbox-2 |

---

## v10.0.0 — Connected Ecosystem

| # | Feature | From |
|---|---|---|
| 60 | Web dashboard (browser-based control panel) | Both |
| 61 | API server (other apps call AI Agent) | Sandbox-2 |
| 62 | Tasker/Automate integration | Sandbox-2 |
| 63 | Smart home control (lights, AC, TV via IoT) | Both |
| 64 | Auto-pilot mode (AI monitors + acts autonomously) | Sandbox-2 |
| 65 | Skill marketplace (share + download skills) | Both |
| 66 | Plugin marketplace (download new tool plugins) | Both |
| 67 | Cross-device sync (rules/memory across phones) | Both |
| 68 | Family mode (each person has own AI profile) | Sandbox-1 |
| 69 | AI-to-AI communication (phone talks to tablet) | Sandbox-1 |

---

## v10.5.0 — Advanced Features

| # | Feature | From |
|---|---|---|
| 70 | Context Camera (point at menu/product/sign → AI sees) | Sandbox-1 |
| 71 | Music identification (like Shazam) | Sandbox-2 |
| 72 | Audio recording + transcription | Sandbox-2 |
| 73 | Wear OS companion (smartwatch app) | Sandbox-2 |
| 74 | Home screen widget | Sandbox-2 |
| 75 | Onboarding wizard (first-launch setup) | Both |
| 76 | Markdown rendering in chat | Both |
| 77 | Dark/Light theme toggle | Both |
| 78 | UI polish (proper layouts, animations) | Both |

---

## UI/UX Improvements (Parallel — Any Version)

| # | Feature |
|---|---|
| 79 | Bottom navigation (5 tabs) |
| 80 | Status orb (animated agent state indicator) |
| 81 | Live status card (per-step progress with elapsed time) |
| 82 | Suggestion chips above input |
| 83 | Proper XML layouts (replace programmatic UI) |

---

## Summary

| Phase | Version | Focus | Feature Count |
|---|---|---|---|
| Bug Fixes | v6.4.0 | Fix what's broken | 6 |
| Reliability | v7.0.0 | Trust + undo + skill recording | 6 |
| Speed + Connectivity | v7.5.0 | Fast + Telegram + diary | 6 |
| Proactive + Developer | v8.0.0 | AI acts first + can code | 9 |
| Image | v8.5.0 | Edit + generate images | 8 |
| Video + Personal | v9.0.0 | Edit/generate video + learn user | 14 |
| Advanced AI + Voice | v9.5.0 | Streaming + caching + voice personality | 10 |
| Ecosystem | v10.0.0 | Web + IoT + marketplace + sync | 10 |
| Advanced Features | v10.5.0 | Camera + audio + wearable + UI | 9 |
| UI/UX | Parallel | Layouts + animations + nav | 5 |
| **TOTAL** | | | **83 features** |

We build one by one. Everything stays in the plan. 🚀

— Sandbox-2 (merged with Sandbox-1's vision)
