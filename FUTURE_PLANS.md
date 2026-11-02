# Aira Future Plans — Comprehensive Roadmap

**Last updated:** November 2, 2026
**Current version:** v4.0.71 (versionCode 199)

## Near-Term (v4.0.72+)

### 1. Capability Levels (escalateLevel)
**Status:** Designed, not implemented
**Effort:** 2 hours
**Token savings:** ~56% on typical daily usage

**Design:**
- Level 1 (Basic): ~15 tools + condensed prompt (~800 tokens)
  - Tools: speak, getTime, getBattery, setBrightness, createRule, remember, recall, searchTools, escalateLevel, complete
  - Prompt: identity + "think before acting" + response format
  - Use: greetings, battery check, rules, quick info

- Level 2 (Standard): ~60 tools + medium prompt (~2000 tokens)
  - Tools: Level 1 + screen, app, browser, file, media, contacts
  - Prompt: thinking + failure reflection + shortcuts + guardrails
  - Use: play music, download files, send messages, browse

- Level 3 (Advanced): 158 tools + full prompt (~3500 tokens)
  - Tools: all + terminal, custom tools, delegateTask
  - Prompt: full system_prompt_smart.txt
  - Use: terminal setup, build projects, install packages, scripts

**escalateLevel tool:**
```kotlin
"escalateLevel" -> {
    val level = (call.args["level"] as? Number)?.toInt() ?: 2
    // Add tools from the requested level
    // Append level-specific instructions to conversation
    // Return: "✅ Escalated to Level $level. You now have access to [tools]."
}
```

**Simplifications:**
- Removes Contextual Prompts toggle (replaced by levels)
- Removes Periodic Reflection toggle (always on)
- Removes Task Planning toggle (always on)
- Removes Post-Action Verification toggle (always on)
- Settings: 10 toggles → 5 toggles

---

### 2. Chat Screen Polish
**Status:** Identified, not started
**Effort:** 2 hours

- Show provider/model in header (ProviderChip already built, just wire it)
- Add empty state with example prompts
- Add "stopping..." state when stopRequested
- Add IME "Send" action to keyboard
- Group header icons (reduce clutter)

---

### 3. Token Optimization
**Status:** Planned
**Effort:** Ongoing

- Capability levels (biggest win — ~56% savings)
- Stop auto-injecting skills (let AI call getSkill) — saves ~200-2000 tokens
- Reduce memory from top 10 to top 5 — saves ~250 tokens
- Re-enable prompt caching (if provider supports) — saves ~3500 tokens
- Batch tool tags (already done v4.0.70) — saves ~33% calls

---

## Medium-Term (v4.1.0+)

### 4. Proactive Behavior System
**Status:** Spec designed (by collaborator AI)
**Effort:** 5 days

**Components:**
1. ProactiveSettings.kt — master toggle, quiet hours, proactive prompt
2. ProactiveObserver.kt — hooks into existing receivers, records patterns
3. ProactiveTickWorker.kt — every 5 min, small LLM call, suggests actions
4. Settings UI — proactive prompt textarea, pattern review

**Key design:**
- User writes freeform personality: "Remind me to call Mom every Sunday"
- LLM interprets each tick: suggest / execute / silent
- Pattern confidence: 5+ occurrences across 3+ days
- Cooldown: 1 hour per pattern, 3 dismissals = permanent
- Quiet hours: 22:00-07:00 (no suggestions unless urgent)
- Auto-execute: opt-in (default OFF for safety)

**Token requirement:**
- Needs capability levels (Level 1 at ~800 tokens/call)
- 288 calls/day × 800 tokens = 230K tokens/day (fits Gemini free tier)
- Without levels: 288 × 10K = 2.88M/day (DOESN'T FIT)

**Spec source:** Provided by collaborator AI, full spec in conversation history.

---

### 5. Sub-Agent Improvements
**Status:** v4.0.69 built (delegateTask)
**Effort:** Ongoing

- Add more agent types: "rules", "memory", "files"
- Sub-agent results shown in chat UI (dedicated card)
- Sub-agent can escalate levels independently
- Sub-agent can create sub-sub-agents (nested delegation)

---

### 6. Settings Cleanup
**Status:** Audited
**Effort:** 1 hour

Remove redundant toggles:
- ❌ Contextual Prompts (replaced by capability levels)
- ❌ Periodic Reflection (always on)
- ❌ Task Planning (always on)
- ❌ Post-Action Verification (always on)
- Merge Auto-Fallback Bindings + Models → one toggle

Keep:
- ✅ Always Learn
- ✅ Knowledge Auto-Recall
- ✅ Consent Flow
- ✅ Auto-Pick Cheapest

---

## Long-Term (v5.0+)

### 7. Hardware Tools (Phone as Software Flipper Zero)
**Status:** Spec designed
**Effort:** 1-3 weeks (phased)

**Phase 1 — Use what phone has (1 week):**
- `scanWifiNetworks()` — SSID, BSSID, signal, security
- `scanBleDevices(duration)` — nearby BLE devices + RSSI
- `findAirTag()` — detect nearby AirTags
- `readNfcTag()` — UID + type + NDEF data
- `irSend(protocol, device, command)` — IR blaster (Xiaomi only)

**Phase 2 — USB OTG (2-3 weeks):**
- `usbSerialSend(data)` — talk to Arduinos, ESP32s
- `usbHidSend(keystrokes)` — BadUSB via USB OTG
- Opens: RTL-SDR, Proxmark3, custom hardware

**Phase 3 — Flipper Zero integration (1-2 weeks, optional):**
- Aira connects to Flipper via BLE
- LLM decides what Flipper action to take
- Pattern Learning saves hardware workflows

**Safety:**
- Read-only tools (scan, read) → no consent needed
- Write tools (emulate, write, send) → consent required
- All hardware ops → logged to action_log
- Pattern Learning NEVER auto-executes hardware tools

---

### 8. Visual Terminal (inside Aira)
**Status:** Discussed, not started
**Effort:** 1-2 weeks

**Option B (recommended):** Embed terminal emulator library
- Terminal display (black bg, monospace, green text)
- Input bar for manual commands
- Uses Android's /system/bin/sh (limited but no external app needed)

**Option C (full standalone):** Bundle Termux bootstrap
- Full Linux environment (pkg, node, python, git)
- APK size: +50-80 MB
- First-launch: extract bootstrap (10-30s)
- Fully standalone — no AndroidIDE dependency

**Decision:** Start with Option B, upgrade to C if needed.

---

### 9. Telemetry / Remote Logging
**Status:** Discussed, not started
**Effort:** 4 hours

- Give Aira to friends → collect usage logs → improve
- Opt-in: "Send anonymous usage data"
- Sends: task type, success/fail, tools used, token usage, errors
- Does NOT send: message content, phone numbers, file contents
- Uses existing Next.js project + Prisma database
- Dashboard: success rates, common failures, tool usage patterns

---

### 10. Remote Screen Control (laptop/other devices)
**Status:** Discussed, not started
**Effort:** 3 hours

- `remoteScreenshot()` — take screenshot on laptop via pyautogui
- `remoteClick(x, y)` — click on laptop screen
- `remoteType(text)` — type on laptop
- `remoteKey(key)` — press key on laptop
- Uses existing terminal connection (runOnTerminal)
- VLM analyzes laptop screenshots (same as phone)
- Could build Aira's APK on laptop remotely

---

## Priority Order

| Priority | Feature | Effort | Depends on |
|----------|---------|--------|------------|
| 1 | Capability levels | 2h | Nothing |
| 2 | Settings cleanup | 1h | Capability levels |
| 3 | Chat screen polish | 2h | Nothing |
| 4 | Token optimization | Ongoing | Capability levels |
| 5 | Proactive behavior | 5 days | Capability levels |
| 6 | Basic hardware (WiFi/BLE) | 1 week | Nothing |
| 7 | Visual terminal | 1-2 weeks | Nothing |
| 8 | Telemetry | 4h | Nothing |
| 9 | Remote screen control | 3h | Terminal connection |
| 10 | Advanced hardware (NFC/USB) | 2-3 weeks | Basic hardware |

---

## Build History Reference

| Version | Key Change | Commit |
|---------|-----------|--------|
| v4.0.55 | Fix false "Done." + reserve pattern_ prefixes | f75b7de |
| v4.0.58 | FIX max_tokens=0 regression | 3744e86 |
| v4.0.60 | HONEST AGENT — remove all "Done." lies | 7b8fd40 |
| v4.0.61 | searchTools — search ALL tools by keyword | 9f233c2 |
| v4.0.62 | TodoWrite — structured planning | f6ec1d9 |
| v4.0.63 | Multi-terminal support + Terminal Hub | 1ff127a |
| v4.0.64 | Full system prompt at all tiers | 8c129a4 |
| v4.0.65 | Sandbox vs terminal distinction | 8e171bc |
| v4.0.66 | Regex fallback for malformed JSON | c334729 |
| v4.0.67 | WhatsApp multi-message (per-contact cooldown) | 26768ed |
| v4.0.68 | Auto-prepend PATH in terminal server | 8cf566e |
| v4.0.69 | Chat redesign (thinking bubbles) + Sub-agents | 861bf8c |
| v4.0.70 | Batch tags on 168 tools + screenshot fix | cdf633d |
| v4.0.71 | listNotifications tool + new app icon | 6f2d384 |
