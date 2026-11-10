# Aira — Conversation Memory

**Last updated:** 2026-11-10
**Purpose:** Persist full conversation context so future sessions (after context reset) can resume without re-discovery.

---

## 1. What Aira Is

**Aira** = AI Agent v2 — a Jetpack Compose Android app that turns the phone into a controllable AI agent.
- Repo: `/home/z/my-project/ai-agent-v2/`
- GitHub: `arun6a/ai-agent-v2` (private)
- Package: `com.ai.agent.v2`
- Min SDK 24, Target SDK 34, Kotlin + Compose BOM 2024.09.02
- 168 tools across 18 categories (screen, apps, vision, browser, web, device, hardware, memory, MCP, location, rules, contacts, files, notifications, media, skills, async, custom)
- 13+ LLM providers (Z.ai, Groq, OpenAI, Anthropic, Gemini, OpenRouter, Cline, Cerebras, Mistral, DeepInfra, Fireworks, Together, local)
- 36 rule trigger receivers (battery, app open, calendar, USB, dock, BT, WiFi, screen, SMS, call, headset, package, boot, etc.)
- Proactive behavior system (ProactiveObserver + ProactiveTickWorker + ProactiveBus)
- MCP client (Model Context Protocol) with OAuth (RFC 9728)
- AI-created connectors with OAuth
- Capability levels (Level 0/1/2/3) for token optimization
- Consent overlay, audit log, pattern learning

**Older sibling:** `ai-agent-apk` (v1, XML layouts, v6.0.4) — battle-tested fallback.

---

## 2. Current Version State

### Committed (HEAD = `d27253f`)
**v4.0.94 (versionCode 222)** — Native function calling for Gemini API

### Uncommitted in working tree
**v4.0.95 (versionCode 223)** — Permission hardening (IN PROGRESS, ~80% done)

Files modified (4):
- `app/build.gradle.kts` — bump 222→223, 4.0.94→4.0.95
- `AndroidManifest.xml` — **14 new permissions** added:
  - TRANSMIT_IR, ANSWER_PHONE_CALLS, READ_PHONE_NUMBERS, BODY_SENSORS, ACTIVITY_RECOGNITION, GET_ACCOUNTS, USE_FULL_SCREEN_INTENT
  - FOREGROUND_SERVICE_{CAMERA,MICROPHONE,LOCATION,BLUETOOTH,CONNECTED_DEVICE}
  - HIGH_SAMPLING_RATE_SENSORS, READ_BASIC_PHONE_STATE
- `DeviceTools.kt` — pre-check WRITE_SETTINGS before setBrightness; Xiaomi/MIUI-specific error hints
- `HardwareTools.kt` — pre-check BLUETOOTH_CONNECT (Android 12+) before getBluetoothState/toggleBluetooth; show BT name+address; Xiaomi hints

**Next action to finish v4.0.95:** commit these 4 files + add BUILDS.md entry.

---

## 3. BUILDS.md Is Behind

`BUILDS.md` documents through **v4.0.87** only. Git log shows v4.0.88–v4.0.94 already committed (7 builds undocumented):

| Version | Commit | Summary |
|---------|--------|---------|
| v4.0.88 | `26e5e3a`, `a2e86da`, `15b2e05`, `094624d` | AI-created connectors with OAuth + connector prompt in all levels + response length caps |
| v4.0.89 | `26e5e3a` | RFC 9728 MCP OAuth discovery + known providers |
| v4.0.90 | `79ccdcd` | Rules in level0/level2 prompts + fix connector over-researching |
| v4.0.91 | `4cbbed7` | Diagnostic logging for Gemini empty response failures |
| v4.0.92 | `5a9c95c` | RAG improvements — searchAll + auto-inject memories + compression ON |
| v4.0.93 | `7f895b9` | Fix 7 bugs from log analysis |
| v4.0.94 | `d27253f` | Native function calling for Gemini API |

**Action needed:** backfill BUILDS.md entries for these 7 versions (use `git show <commit>` to extract details).

---

## 4. The Antigravity / Shizuku Discussion (DEFERRED)

### Finding
**Antigravity** (a similar Android app) achieves system-level control WITHOUT root and WITHOUT Shizuku by:
1. Bundling a minimal ADB client library (`adblib`, ~2MB) inside the APK
2. User enables Wireless Debugging ONCE (Settings → Developer Options)
3. User pairs once (enters pairing code in the app)
4. App connects to `127.0.0.1:5555` internally → runs as `uid=2000` (ADB shell user)
5. All `pm install`, `settings put`, `am force-stop` commands work — no root, no Shizuku app

### vs Shizuku
| | Shizuku | Antigravity approach |
|---|---------|---------------------|
| Separate app? | Yes | No (self-contained) |
| ADB source | rish/adb shell service | Bundled adblib |
| Reboot | Needs Shizuku restart | Pairing persists, auto-reconnect |
| APK cost | +8MB (Shizuku) | +2MB (adblib) |

### Decision
**Aira can do the same** — bundle `adblib`, connect to localhost wireless debugging, get `uid=2000` access. All current permission errors (WRITE_SETTINGS, BLUETOOTH_CONNECT, INSTALL_PACKAGES, etc.) would be solved at the source.

**Status:** DEFERRED. User said: "We do add Shizuku antigravity finding later in future now come back to what other things we can add."

---

## 5. Existing Roadmap (FUTURE_PLANS.md — already documented)

10 items already on the roadmap. DO NOT re-propose these:

1. Capability Levels (escalateLevel) — **DONE v4.0.72**
2. Chat Screen Polish — **DONE v4.0.87**
3. Token Optimization — ongoing
4. Proactive Behavior System — **DONE v4.0.78**
5. Sub-Agent Improvements — partial (delegateTask done v4.0.69)
6. Settings Cleanup — done
7. Hardware Tools (Software Flipper Zero) — **DONE v4.0.76–v4.0.77** (basic + USB + NFC + BLE + IR)
8. Visual Terminal — partial (TerminalManager exists)
9. Telemetry / Remote Logging — not started
10. Remote Screen Control — **DONE v4.0.76** (remoteScreenshot, remoteClick, remoteType, remoteKey)

---

## 6. NEW Feature Ideas Proposed (NOT yet on roadmap)

Proposed in this session, NOT yet built. Organized by theme:

### A. Voice & Conversational UX
- **A1. Wake-word "Hey Aira"** — Porcupine/Vosk on-device KWS, ~3% battery/hr, 3-4 days
- **A2. Full-duplex conversation** — continuous STT + VAD, no tap-to-send, 2-3 days
- **A3. Voice cloning for TTS** — Coqui XTTS-v2 / your own voice, 5-7 days

### B. Camera / Always-on Vision
- **B1. Double-tap-power "Describe what I see"** — camera→VLM→speech, 2 days
- **B2. Document scanner skill** — edge detect + rectify + OCR → PDF, 3 days
- **B3. Live camera translation** — AR overlay translated text, 4 days
- **B4. QR/Barcode always-scanning** — background detection + consent, 2 days

### C. Automation & Workflows
- **C1. Visual workflow builder** — drag-drop node graph → JSON skill, 1-2 weeks
- **C2. Macro recorder** — record screen actions → replay as skill, 3 days
- **C3. Share-sheet integration** — Android Share → "Send to Aira", 1 day ⭐
- **C4. Incoming webhook triggers** — NanoHTTPD endpoint for GitHub/Stripe, 2 days

### D. Multi-Device & Sync
- **D1. Web companion dashboard** — this Next.js project as live control panel, 1 week ⭐
- **D2. E2E-encrypted cloud memory sync**, 4 days
- **D3. Wear OS companion**, 1 week

### E. Privacy & Trust
- **E1. Audit log timeline UI** — browsable action history, 2 days ⭐
- **E2. Incognito mode** — no memory/learning for session, 1 day
- **E3. "What does Aira know about me" dashboard** — GDPR-style self-service, 2 days
- **E4. Local-first LLM option** — Gemini Nano / MLC-LLM offline, 5 days

### F. Learning & Personalization
- **F1. Preference extraction** — passively infer "prefers WhatsApp over SMS", 3 days ⭐
- **F2. Routine detection** — "opens Spotify 8:10am weekdays", 4 days
- **F3. Smart skill suggestions** — "did X 6 times, create skill?", 2 days

### G. Ecosystem Integrations
- **G1. Home Assistant connector** — local HA API, 2 days
- **G2. Email triage skill** — Gmail API classify + draft, 4 days
- **G3. Expense tracking from SMS** — UPI/banking SMS parser, 3 days
- **G4. Tasker plugin** — bidirectional, 3 days

### H. Communication Intelligence
- **H1. Call screening** — Google Call Screen clone via Telecom API, 5 days
- **H2. Group chat summarization** — notification digest, 2 days
- **H3. Smart compose overlay** — inline reply chips in any chat, 4 days

### I. Power-user / Developer
- **I1. Debug console** — live LLM req/resp + tool calls + tokens, 1 day ⭐
- **I2. Token budget manager** — hard daily cap + model fallback, 1 day
- **I3. Prompt profile switcher** — Work/Personal/Coding profiles, 1 day

### Top 5 ROI picks (recommended order)
1. C3 Share-sheet integration (1 day)
2. E1 Audit log timeline (2 days)
3. I1 Debug console (1 day)
4. D1 Web companion dashboard (1 week)
5. F1 Preference extraction (3 days)

---

## 7. Mini-Services Ecosystem (this sandbox)

Three mini-services run in the sandbox (Bun/Node, separate ports, supervised via double-fork):

| Service | Port | Purpose | External URL |
|---------|------|---------|--------------|
| `ai-proxy` | 3030 | Z.ai SDK proxy (chat, search, image-gen, TTS, image-search) | `https://preview-chat-1855dd56-...space-z.ai/?XTransformPort=3030` |
| `wa-bot` | 3031 | WhatsApp AI bot (Baileys + multi-AI) | `?XTransformPort=3031` |
| `ai-proxy-template` | (on-demand) | Reusable scaffold for new projects | — |

All use `XTransformPort` query param for gateway routing. CORS `*` enabled. Auto-restart supervisors in `/home/z/my-project/.zscripts/`.

---

## 8. Sandbox / Build Constraints

- **Cannot build APK in sandbox** — Gradle dependency download too slow (>10min timeout). User builds on their machine.
- Dev server: `bun run dev` on port 3000 (Next.js), logs at `/home/z/my-project/dev.log`
- User timezone: Asia/Calcutta
- GitHub PAT for `arun6a` is available (used for past pushes)
- z-ai-web-dev-sdk MUST be backend-only

---

## 9. THE LOGS.TXT ANALYSIS (recovered 2026-11-10)

**CRITICAL CONTEXT:** This is what we were doing before the Antigravity tangent.

User uploaded `/home/z/my-project/upload/logs.txt` — 4717 lines of real Aira runtime logs from the phone, capturing a **40-task test suite** the AI ran against itself. The AI executed 136 tool calls: 114 succeeded, 22 failed (84% success rate).

### The Actual Sequence of Events
1. User shared logs.txt for analysis
2. I analyzed it, identified the bugs below
3. I started fixing them, was about to build
4. **User stopped me** → pivoted to Antigravity app discussion
5. Discussed Antigravity vs Shizuku vs Aira (ADB client approach) — Section 4
6. User said "come back to aira agent ai v2"
7. **I forgot the logs.txt work entirely** and picked up at v4.0.95 (permission hardening, which was already in the working tree)
8. Built v4.0.95, pushed to GitHub
9. User reminded me: "We are checking the logs.txt, and you give some suggestions to fix and while you fixing and ready to build i stopped and i bring topic about antigravity app then i switch topic to aira you for get"

### Bugs Found in logs.txt (22 failures, grouped by root cause)

#### A. CRITICAL: List/Array Parser Bug (3 tools affected)
The tool argument parser is rejecting valid JSON arrays passed by the AI:

1. **`todoWrite` (5 failures)** — AI passes `todos=[{...},{...}]` as a valid JSON array, parser says "missing todos"
2. **`actionChain` (1 failure)** — AI passes `actions=[{name,args},{name,args}]`, parser says "missing 'actions' list"
3. **`createConnector` (1 failure)** — AI passes `endpoints=[{...}]`, parser says "missing endpoints (JSON array)"

**Root cause hypothesis:** The parser expects a stringified JSON but the AI passes a native array (from native function calling). The v4.0.94 Gemini native function calling fix may have exposed this — now arrays arrive as native lists instead of strings.

#### B. Parameter Alias Bugs (2 tools)
4. **`recallMemory(key=...)` (1 failure)** — AI calls with `key=` but tool expects `query=`. Needs alias (like v4.0.93 `packageName` fix for `launchApp`)
5. **`speak(0=Please charge your phone)` (1 failure)** — AI passes positional arg `0=` instead of `text=`. Needs positional arg support OR better schema clarity

#### C. Permission Errors (3 tools) — PARTIALLY FIXED
6. **`setBrightness` → WRITE_SETTINGS** ✅ FIXED in v4.0.95 (pre-check + MIUI hint)
7. **`getBluetoothState` → BLUETOOTH_CONNECT** ✅ FIXED in v4.0.95 (pre-check + MIUI hint)
8. **`setAlarm` → Permission Denial: starting Intent SET_ALARM** ❌ NOT FIXED — needs `com.android.alarm.permission.SET_ALARM` permission OR foreground intent approach

#### D. Schema Clarity Issues (3 tools)
9. **`createRule(triggerValue="every 2 hours", triggerType="time")`** — `time` type expects `HH:MM` but AI passed natural language. Needs an `interval` triggerType for recurring schedules
10. **`createConnector` → "missing description"** — description param should be optional or the schema should make it clearer
11. **`createCalendarEvent` → "missing startTime"** — AI didn't pass startTime; schema needs to make required fields clearer

#### E. False Positive Warning (99 occurrences!)
12. **"⚠️ GEMINI EMPTY CONTENT" fires even when Gemini returns function calls** — 99 warnings in the log, but 113 functionCall objects were successfully parsed. The v4.0.91 diagnostic logging logs "EMPTY CONTENT" whenever there's no text, even when functionCall IS present. Should be downgraded to debug-level or removed now that v4.0.94 confirmed function call parsing works.

#### F. Minor/Expected
13. **`getWeather` → "GPS not available"** (2 failures) — expected, GPS was off
14. **`searchMcpServers` → "MCP is disabled"** — expected, user had MCP off
15. **`speak` → "TTS error: StandaloneCoroutine was cancelled"** — agent was stopped mid-speech, expected
16. **`clickByText(AI Settings)`** — accessibility couldn't find the text on screen
17. **`delegateTask(screen)` completed in 5 iterations** — sub-agent hit iteration limit, not a crash

#### G. Honesty System Working ✅
Line 4399: `complete(success=true) but 4 tools failed — overriding to success=false` — The v4.0.60 "honest agent" system correctly caught the AI lying about success.

### The Fix Plan (what I was about to build before the interruption)

**v4.0.96 — Log Analysis Fixes:**
1. Fix list/array parser in `ToolExecutor.kt` (affects todoWrite, actionChain, createConnector) — HIGHEST PRIORITY, 7 failures
2. Add `key=` alias to `recallMemory` (like v4.0.93 packageName fix)
3. Add `interval` triggerType to `createRule` for recurring schedules
4. Make `description` optional in `createConnector`
5. Add `SET_ALARM` permission to manifest + handle foreground intent
6. Remove or downgrade "GEMINI EMPTY CONTENT" false positive warning

This is the work that was interrupted. Resume here.

---

## 10. Immediate Next Steps (decision pending)

User was asked to pick one:
1. **Finish v4.0.95** — commit permission work + BUILDS.md entry ✅ DONE
2. **Backfill BUILDS.md** — write entries for v4.0.88–v4.0.94 ✅ DONE
3. **Start v4.0.96** — resume the logs.txt fix work from Section 9 above
4. **Something else** — user-directed

User's last message: confirming the logs.txt context loss. Need to acknowledge the lost context and offer to resume the log-fix work.

---

## 10. Key File Locations (quick reference)

| What | Path |
|------|------|
| Aira v2 source | `/home/z/my-project/ai-agent-v2/` |
| Build history | `/home/z/my-project/ai-agent-v2/BUILDS.md` |
| Future plans | `/home/z/my-project/ai-agent-v2/FUTURE_PLANS.md` |
| Tool executor | `ai-agent-v2/app/src/main/java/com/ai/agent/tools/ToolExecutor.kt` |
| Tool schemas | `.../llm/ToolSchemas.kt` (75KB, all 168 tool definitions) |
| LLM client | `.../llm/LLMClient.kt` (81KB) |
| AI providers | `.../llm/AIProvider.kt` (37KB, 13+ providers) |
| Accessibility svc | `.../accessibility/AgentAccessibilityService.kt` |
| Proactive system | `.../proactive/` (ProactiveBus, Observer, Scheduler, TickWorker, Settings) |
| MCP client | `.../mcp/` (McpManager, McpClient, McpTools, McpOAuth, McpRegistryClient) |
| Old APKs | `/home/z/my-project/public/ai-agent-v*.apk` (v4.0.0, v4.0.1, v4.1.0, v6.0.1–v6.0.4) |
| Worklog | `/home/z/my-project/worklog.md` (sandbox tasks, not Aira-specific) |
| Conversation memory | `/home/z/my-project/ai-agent-v2/CONVERSATION_MEMORY.md` ← THIS FILE |

---

## 11. Conversation Tone & Preferences (observed)

- User works fast, builds iteratively, ships APKs to physical devices
- User uses Xiaomi/MIUI devices (frequent MIUI-specific permission issues)
- User prefers concise, actionable responses over long explanations
- User values "honest agent" behavior (v4.0.60 removed all "Done." lies)
- User has a collaborator AI that writes specs (Proactive Behavior System spec came from there)
- User builds APKs on their own machine, sandbox is for code + planning
- User pushes to GitHub after each version
