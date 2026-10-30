# Aira — Build History

Complete changelog for every build of Aira (AI Agent v2).

---

## v4.0.50 (versionCode 178) — Remove Forced Timeout + Fix PTY Hanging + Step Timeout Fix
**Date:** 2026-10-30

### Major Changes

#### 1. Removed Forced Timeout — AI Decides Per-Command
- **Server default timeout**: 120s → **0 (infinite — no kill)**
- **Server max cap**: 600s → **No cap (AI picks any value)**
- **Client readTimeout** (`CustomTools.kt`): 305s → **0 (infinite — never disconnects)**
- **Client callTimeout** (`CustomTools.kt`): 310s → **0 (infinite)**
- The `timeout` parameter is now **opt-in** instead of forced
- AI omits `timeout` for builds/installs (let them finish); passes `timeout=60` only for commands that might hang

#### 2. Fixed PTY Server Hanging (was the real cause of 60s timeouts)
**Root cause:** The v4.0.49 server used `script -qc` with `stdio: ['pipe','pipe','pipe']` which **buffered stdout** — simple commands like `echo hello` would hang for 60s because the pipe never flushed.

**Fix:**
- Changed `script -qc` → `script -qfc` (the `-f` flag forces flush after each write)
- Auto-Y timer: was firing blindly after 3s for ALL commands (even `echo`) → now only fires when a prompt pattern (`[Y/n]`, `yes/no`, `?`) is detected in output, with a 5s fallback
- Added `proc.stdin.end()` on close to prevent lingering pipes
- Added `autoYesUsed` field to response so AI can see if auto-Y was sent

#### 3. Fixed Step Timeout in ChatViewModel (THE REAL KILLER)
**Root cause:** `ChatViewModel.kt:985` had `STEP_TIMEOUT_MS = 60_000L` — a per-iteration budget that killed ANY tool call taking >60s. Even with infinite OkHttp + server timeouts, this 60s limit would still abort `run_command` calls.

**Evidence from logs:**
```
21:08:50 W/ChatViewModel: Step 1 timed out after 60000ms
21:08:56 W/ChatViewModel: Step 2 timed out after 60000ms
21:12:52 W/ChatViewModel: Step 1 timed out after 60000ms
21:22:10 W/ChatViewModel: Step 2 timed out after 60000ms
```

**Fix:** `STEP_TIMEOUT_MS`: 60s → **600s** (10 min, matches TASK_TIMEOUT_MS). Also fixed the hardcoded "30s" in the timeout error message to use the actual value.

#### 4. Timeout Guide Updated
- **OMIT timeout** for: builds, installs, file ops, scripts (should finish)
- **Pass `timeout=10`** for: `ping`, `tail -f` (run forever)
- **Pass `timeout=60`** for: risky commands that might hang (safety net)

### Three Layers of Timeout (all now aligned)

| Layer | Location | v4.0.49 | v4.0.50 |
|-------|----------|---------|---------|
| 1. OkHttp client | `CustomTools.kt:36-37` | 305s | **0 (infinite)** |
| 2. Server execSync | `aira-server.js` | 120s default, 600s max | **0 (infinite), AI controls** |
| 3. ChatViewModel step | `ChatViewModel.kt:990` | 60s | **600s** |

### Files Changed
- `app/build.gradle.kts` — version 4.0.49 → 4.0.50, versionCode 177 → 178
- `app/src/main/java/com/ai/agent/tools/categories/CustomTools.kt` — readTimeout/callTimeout → 0 (infinite)
- `app/src/main/java/com/ai/agent/ui/screens/ChatViewModel.kt` — STEP_TIMEOUT_MS 60s → 600s + fixed error message
- `app/src/main/assets/skills/setup_terminal_access.md` — server default timeout=0, `script -qfc`, smart auto-Y
- `app/src/main/assets/skills/terminal_commands.md` — timeout is opt-in, omit for builds/installs

### Migration Notes
- **Server restart required**: User must restart the server after updating the skill
- **Recreate run_command tool**: Tell Aira "set up terminal access" to write the new server + recreate the tool
- **Backward compatible**: Old server + new APK works (just 60s step timeout still applies until server updated)

---

## v4.0.49 (versionCode 177) — Terminal PTY Support + Timeout Parameter + Optional Tool Params
**Date:** 2026-10-30

### Major Changes

#### 1. PTY-Based Terminal Server (Option A)
- Rewrote `aira-server.js` (in `setup_terminal_access.md` skill) to use `child_process.spawn` instead of `execSync`
- **Auto-detects PTY support** via `script` command at startup:
  - If `script` available → PTY mode (`script -qc "cmd" /dev/null`) — real pseudo-terminal
  - If not → fallback to direct spawn with `/system/bin/sh`
- **Interactive prompts auto-answered** — feeds `y\n` to stdin after 3 seconds (handles apt/pkg Y/n prompts)
- **ANSI escape codes stripped** from output for cleaner AI parsing
- **Removed `source ~/.profile` auto-prepend** — was causing `Syntax error: end of file unexpected` in dash shell
- Response now includes: `exitCode`, `timedOut`, `elapsed`, `pty` mode

#### 2. Timeout Parameter for `run_command` (Option 2)
- `run_command` custom tool now accepts optional `timeout` parameter
- Default: 120s. Max: 600s (10 min). Client-side cap: 305s.
- AI can pass `timeout=300` for builds, `timeout=180` for package installs
- **`CustomTools.kt`**: bumped OkHttp `readTimeout` from 30s → 305s, added `callTimeout(310s)`
- **Optional parameter support**: HTTP custom tools now strip unresolved `{param}` placeholders from URL instead of failing — enables optional parameters like `timeout`

#### 3. Skill File Updates
- `terminal_commands.md`:
  - Replaced all `which` → `command -v` (POSIX builtin, always works in dash)
  - Documented `apt WARNING` lines as expected noise (IGNORE them)
  - Documented `idesetup` as bash-only + interactive — cannot run via `run_command`
  - Added timeout guidance: `timeout=300` for builds, `timeout=180` for installs
  - Updated "How to Interpret Results" section for new v4.0.49 response format
- `setup_terminal_access.md`:
  - Full rewrite with PTY server code, timeout parameter, createCustomTool with `parameters="command,timeout"`
  - Added PTY detection step, timeout guide table, troubleshooting for `timedOut`/`pty` fields

### Root Causes Fixed
| Issue | Root Cause | Fix |
|-------|-----------|-----|
| `which: not found` | dash shell has no `which` binary | Use `command -v` (POSIX builtin) |
| `Syntax error: end of file unexpected` | Server auto-prepended `source ~/.profile` which aborts in dash | Removed auto-prepend; callers set PATH explicitly |
| `idesetup -c` timeout (30s) | OkHttp `readTimeout=30s` killed the connection before server's 120s | Bumped to 305s + added `timeout` parameter |
| `idesetup` syntax error | bash-only script parsed by dash | PTY via `script` + documented as manual-only |
| apt warnings (×4) treated as errors | `pkg` wraps `apt` which prints warnings | Documented as expected noise |

### Files Changed
- `app/build.gradle.kts` — version 4.0.48 → 4.0.49, versionCode 176 → 177
- `app/src/main/java/com/ai/agent/tools/categories/CustomTools.kt` — readTimeout 30s→305s, optional params
- `app/src/main/assets/skills/setup_terminal_access.md` — full rewrite (PTY server + timeout)
- `app/src/main/assets/skills/terminal_commands.md` — which→command -v, timeout guidance, PTY notes

### Migration Notes
- **Existing `run_command` tools**: The old tool (without `timeout` param) still works — the server uses 120s default. To get the new timeout support, Aira must recreate the tool via `createCustomTool` with `parameters="command,timeout"`.
- **Server restart required**: User must restart the server after updating the skill: `node /sdcard/Aira/aira-server.js &`
- **Backward compatible**: Old server + new APK works (just no timeout/PTY). New server + old APK works (just 30s client timeout still applies).

---

## v4.0.39 (versionCode 167) — Tools Section Reorganized + Custom Tools Visible
**Date:** 2026-10-29
**Commit:** `4aa2cf6`

### Changes
- Reorganized Tools section with proper categories (5 new: Search, Device Info, Permissions, Custom Tools, System)
- Custom tools (created by AI via `createCustomTool`) now visible in Hub → Tools
- Misc category reduced from ~20 tools to 0-2 (only truly uncategorized)

---

## v4.0.38 (versionCode 166) — Working Directory Changed to /Aira/
**Date:** 2026-10-29
**Commit:** `f7cba2a`

### Changes
- Changed working directory from `/storage/emulated/0/Documents/ai-workspace/` → `/storage/emulated/0/Aira/`
- Added central `Config.WORKSPACE_DIR` constant
- Automatic migration: old files copied to new location on first launch

---

## v4.0.37 (versionCode 165) — Pattern Learning + Device Calibration
**Date:** 2026-10-29
**Commit:** `4905f8d`

### Major Features
1. **Pattern Learning**: When `complete(success=true)` is called, saves the successful tool sequence as `pattern_*` memory. Next similar task → AI uses known-good sequence (saves 2-3 API calls).
2. **Device Calibration**: When `clickByText`/`clickElement` succeeds, saves app+element+result as `calib_*` memory. AI learns which clicks work on YOUR device.
3. Removed screen resolution from system prompt (was encouraging coordinate guessing)
4. Quiet VLM errors (401 logged as WARN, not ERROR — a11y fallback works silently)

---

## v4.0.34 (versionCode 162) — Clean Error Messages + Improved 402 Retry
**Date:** 2026-10-28
**Commit:** `7cafdf3`

### Changes
- Removed all provider recommendations (Z.ai, Cline, Groq) from error messages
- Error messages now neutral: "Add credits at the provider's dashboard, or add a new API key in Hub → LLM → Configure."
- Improved 402 retry: extracts ALL "can only afford N" values from nested `previous_errors`, uses smallest

---

## v4.0.33 (versionCode 161) — Fix Prompt Too Large for Free Tier
**Date:** 2026-10-28
**Commit:** `04d2b8b`

### Changes
- Pre-flight token budget check: estimates total tokens, reduces `max_tokens` if over 3192 limit
- Prompt-too-large fallback: retries with minimal tools (8) → no tools (text-only)
- Fixes "try again" failing with 16605 tokens on OpenRouter free tier

---

## v4.0.32 (versionCode 160) — Headless Google Search + API Keys + Deep Links
**Date:** 2026-10-28
**Commit:** `42e60de`

### 7 New Tools
1. `headlessGoogleSearch(query)` — Google search via off-screen WebView (no CAPTCHA!)
2. `headlessSearch(query, engine)` — generic headless WebView search
3. `braveSearch(query, limit)` — official Brave Search API (2000/month free)
4. `googleSearchApi(query, limit)` — official Google Custom Search API (100/day free)
5. `smartSearch(query)` — tries Google API → Brave → Bing → Headless Google
6. `navigateToAppScreen(screen)` — AI opens any app screen (settings, permissions, etc.)
7. `listAppScreens()` — lists available screens

### Settings UI
- New "Search API Keys" section: Brave + Google API key inputs with status badges

---

## v4.0.30 (versionCode 158) — 14 New Permission + Device Info Tools
**Date:** 2026-10-28
**Commit:** `fb695cc`

### 14 New Tools
**Permission-based:** readSms, vibrate, getPhoneState, wakeLock
**Device info:** getBrightness, getFlashlightState, getDeviceUptime, getMemoryInfo, getStorageInfo, getBatteryHealth, getScreenOrientation, isCharging, getRingtoneMode, setRingtoneMode

---

## v4.0.26 (versionCode 155) — AI Can CREATE New Tools at Runtime
**Date:** 2026-10-28
**Commit:** `de41b17`

### Major Feature
- `createCustomTool(name, description, handlerType, handler, parameters)` — AI creates new tools!
- Two handler types: `shell` (runs shell command) + `http` (makes HTTP request)
- Tools persist across app restarts (saved to JSON)
- `listCustomTools()` + `deleteCustomTool(name)` for management

---

## v4.0.20 (versionCode 149) — Fixed Rules + Skills System (5 Root Causes)
**Date:** 2026-10-28
**Commit:** `2daad30`

### 5 Root Cause Fixes
1. Context-aware arg alias (`name→package` only for package-taking tools)
2. Implemented `parseArgsAsKeyValue` (was called but never defined)
3. Resolved prompt/tools conflict (native function calling vs JSON text)
4. RuleEngine routed through `chatWithFailover` + real prompt
5. Hardened text parser (comma-in-value handling)

---

## v4.0.19 (versionCode 148) — Native Function Calling Fix
**Date:** 2026-10-28
**Commit:** `f361200`

### Changes
- Re-enabled native function calling for OpenRouter
- Fixed case-insensitive tool name matching
- Added `complete` to noAccessibilityNeeded

---

## v2.3.3 (versionCode 24) — getWeather + webSearch fetchTop + AppProfileManager
**Date:** 2026-10-12
**Commit:** `145c93d`
**APK:** 43.3 MB

### Major Changes

#### 1. getWeather tool (1 call vs 3+)
- **Problem:** "What's the weather?" used to require webSearch → fetchPageText loop = 3+ API calls
- **Solution:** New `getWeather()` tool — uses GPS + free Open-Meteo API (no API key, works globally including India)
- **Returns:** Current temp, feels-like, humidity, wind, precipitation, condition, location + 3-day forecast
- Added to all 3 prompt files (smart/balanced/fast) + DynamicPromptBuilder Device category
- Added to batchable READ-ONLY list — can be batched with getBatteryLevel, getCurrentTime, etc.
- DynamicPromptBuilder keywords expanded: `weather`, `temperature`, `forecast`, `rain` → Device category

#### 2. webSearch fetchTop — 1 call instead of 1+N
- **Problem:** webSearch returned 8 short snippets → AI called fetchPageText on each → 1+8 = 9 API calls per search
- **Solution:** New `fetchTop=N` param on webSearch
  - When fetchTop=1 → returns search results + full text of #1 result in 1 call (saves 1 API call)
  - When fetchTop=2 → returns search results + full text of top 2 results in 1 call (saves 2 API calls)
  - Concurrent fetch via `kotlinx.coroutines.async` + `awaitAll` on IO dispatcher (parallel HTTP)
- **Prompt rule added:** "After webSearch without fetchTop: ANSWER FROM SNIPPETS. Only call fetchPageText if snippets incomplete OR user explicitly asks for full article."
- **Example workflow:** "what is quantum computing" → webSearch("quantum computing", fetchTop=1) → done in 1 call

#### 3. Removed PluginManager (10 JSON files + Kotlin class)
- **Problem:** PluginManager + 10 JSON files in `assets/plugins/` just re-listed tools that ALREADY exist in ToolExecutor + system prompts. Wasted ~3000 tokens per request.
- **Removed:**
  - `app/src/main/java/com/ai/agent/llm/PluginManager.kt`
  - `app/src/main/assets/plugins/{browser,calendar,calendar_extended,communication,device,device_advanced,media_advanced,social_media,system,whatsapp}.json`
  - HubScreen "Plugins" section + PluginCard composable
  - Plugin injection in `AgentService.kt` + `ChatViewModel.kt`

#### 4. New AppProfileManager — replaces PluginManager
- **Insight:** Instead of duplicating tool lists, AppProfileManager knows what apps are ACTUALLY INSTALLED on this device and tells the AI how to interact with each one.
- **18 built-in profiles:**
  - **Messaging:** WhatsApp (openWhatsAppChat), Telegram (openTelegramChat), Messenger, Gmail (composeEmail)
  - **Social:** Instagram (openInstagramProfile), YouTube (searchInApp), X/Twitter, Facebook
  - **Media:** Spotify (playSpotify), YouTube Music, Netflix
  - **Shopping:** Amazon, Flipkart
  - **Utility:** Google Maps (openMapsLocation), Chrome, Phone (makePhoneCall)
  - **Productivity:** Google Keep, Calendar (createCalendarEvent)
- **Auto-detect:** Scans `PackageManager` on first launch → marks installed profiles
- **Privacy control:** User toggles per-app AI access (Hub → Apps section)
- **Custom apps:** User can add custom app profiles (packageName + displayName + category)
- **Prompt section:** ~150-300 tokens listing only installed+enabled apps with shortcut tool + tip
- **Token cost:** 10-20× smaller than old PluginManager

### Modified Files
- `app/build.gradle.kts` — versionCode 23 → 24, versionName 2.3.2 → 2.3.3
- `app/src/main/assets/system_prompt_smart.txt` — getWeather added (3 locations), webSearch fetchTop docs, anti-fetchPageText-loop rule
- `app/src/main/assets/system_prompt_balanced.txt` — getWeather + webSearch fetchTop
- `app/src/main/assets/system_prompt_fast.txt` — getWeather + webSearch fetchTop
- `app/src/main/java/com/ai/agent/llm/AppProfileManager.kt` — NEW (380 lines)
- `app/src/main/java/com/ai/agent/llm/DynamicPromptBuilder.kt` — getWeather in Device category + weather keywords + webSearch fetchTop docs
- `app/src/main/java/com/ai/agent/tools/ToolExecutor.kt` — getWeather impl (134 lines) + webSearch fetchTop impl (74 lines) + param spec update
- `app/src/main/java/com/ai/agent/service/AgentService.kt` — PluginManager → AppProfileManager
- `app/src/main/java/com/ai/agent/ui/screens/ChatViewModel.kt` — PluginManager → AppProfileManager
- `app/src/main/java/com/ai/agent/ui/screens/HubScreen.kt` — PluginsSection + PluginCard → AppsSection + AppCard

### Removed Files
- `app/src/main/java/com/ai/agent/llm/PluginManager.kt`
- `app/src/main/assets/plugins/*.json` (10 files)

### API Call Savings (per task)
- Weather query: 3+ calls → **1 call** (getWeather)
- "What is X" research: 1+5=6 calls → **1 call** (webSearch fetchTop=1)
- App interaction (WhatsApp/Spotify/etc): already 1 call (unchanged)
- Per-prompt token cost: ~3000 → ~150-300 (10-20× smaller from AppProfileManager)

### Download
- APK: `ai-agent-v2.3.3-debug.apk` (43.3 MB)
- Source: https://github.com/arun6a/ai-agent-v2/commit/145c93d

---

## v2.3.2 (versionCode 23) — Group step messages + WebView on top + Rules edit/delete
**Date:** 2026-10-12
**Commit:** `99f1cb3`
**APK:** 43.3 MB

### v2.3.2 Changes (already released)
- Step messages grouped into single collapsible card
- WebView fixed to display on top of chat
- Rules support edit/delete via long-press menu

---

## v2.3.0 (versionCode 21) — Embedded WebView
**Date:** 2026-10-12
**Commit:** `67fd976`
**APK:** 43.3 MB

### Root Fix: BrowserActivity ANR → a11y disconnection
- **Problem:** BrowserActivity was a separate Activity. When AI launched it, MIUI detected a foreground change → unbound the accessibility service → "Accessibility not enabled". Heavy onCreate → ANR. runBlocking in BrowserController → 15s main thread freeze.
- **Solution:** Embedded WebView inside ChatScreen (Gemini's Option 2). NO Activity switch → MIUI never sees foreground change → a11y stays alive.

### New Files
- `EmbeddedWebView.kt` — Full-screen WebView overlay composable (AndroidView inside ChatScreen's Box)
- `EmbeddedBrowserBridge.kt` — Static bridge for ToolExecutor → ChatViewModel (WeakReference + reflection)

### Modified Files
- `ChatViewModel.kt` — Added webViewVisible, webViewUrl, webViewUserMode state + show/hide/setUserMode/resumeAiMode methods + EmbeddedBrowserBridge registration in init
- `BrowserController.kt` — Added embeddedWebView field + setEmbeddedWebView/clearEmbeddedWebView + getWebView checks embedded first + pageLoadDeferred changed to internal
- `ToolExecutor.kt` — browserOpen now uses EmbeddedBrowserBridge.show() instead of BrowserActivity.launch() for auto/in_app modes
- `ChatScreen.kt` — Added EmbeddedWebView overlay in Scaffold Box

### Key Changes
- AI calls browserOpen → EmbeddedBrowserBridge → ChatViewModel shows overlay → WebView created via AndroidView → BrowserController gets reference → AI tools work on embedded WebView
- User takeover detection: tap WebView → User Mode (AI pauses)
- "← Chat" button → WebView destroyed (saves RAM)
- BrowserActivity kept for manual use (user taps browser icon)
- AnimatedVisibility for smooth show/hide

---

## v2.2.7 (versionCode 20) — ANR Root Cause Fix
**Date:** 2026-10-12
**Commit:** `2ccb2f4`
**APK:** 43.3 MB

### Root Cause Found + Fixed
1. **runBlocking in BrowserController** — Was blocking main thread for up to 15s while waiting for page to load. Removed runBlocking entirely.
2. **Agent loop moved to Dispatchers.IO** — Was running on Main dispatcher. Changed to `viewModelScope.launch(Dispatchers.IO)`.
3. **Thread.sleep(500) removed** from BrowserController.ensureBackgroundWebView()
4. **All OkHttp .execute() wrapped in withContext(IO)** — makeHttpRequest, fetchPageText, downloadFile, webSearch

---

## v2.2.6 (versionCode 19) — Thread.sleep → delay + webSearch Fix
**Date:** 2026-10-12
**Commit:** `e832268`
**APK:** 43.3 MB

### Fixes
1. **browserOpen ANR** — Thread.sleep(2000) replaced with non-blocking delay(2000) (5 occurrences)
2. **webSearch NetworkOnMainThreadException** — OkHttp .execute() wrapped in withContext(Dispatchers.IO)
3. **Google Search App** — Added 'google_app' search method option (opens ACTION_WEB_SEARCH Intent)

---

## v2.2.5 (versionCode 18) — BrowserActivity ANR + A11y Reconnection
**Date:** 2026-10-12
**Commit:** `d884838`
**APK:** 43.2 MB

### Fixes
1. **BrowserActivity ANR** — Deferred WebView creation to onResume() + postDelayed(100ms). onCreate() now just sets up layout (fast, no blocking).
2. **waitForAccessibilityService poll increased** — 5s → 10s (20 × 500ms)
3. **Notification a11y status** — Changed from isRunning() to isEnabled(context) which checks Android system setting

---

## v2.2.4 (versionCode 17) — ML Kit Threshold + Collapsible Messages
**Date:** 2026-10-12
**Commit:** `3ad6c2f`
**APK:** 43.3 MB

### Fixes
1. **ML Kit auto threshold** — Raised from 200 to 500 chars so ML Kit supplements more often
2. **Collapsible step messages** — Step messages start collapsed (first line + tool count + "tap to expand ▶"). Tap to expand/collapse with animateContentSize()

---

## v2.2.3 (versionCode 16) — Vision Mode + ML Kit + Screen Loading
**Date:** 2026-10-12
**Commit:** `202835c`
**APK:** 43.3 MB

### New Features
1. **Vision Mode setting** — 3 options: auto (default) | mlkit | vlm in Hub → Settings → VISION MODE
2. **readScreen() rewritten** — Supports all 3 vision modes with smart fallback
3. **captureAndOCR() helper** — Reusable screenshot + ML Kit OCR pipeline
4. **Screen loading guidance** — System prompt instructs AI to wait + readScreen after launchApp/browserOpen
5. **App logo** — Added to design/assets/ in repo

---

## v2.2.2 (versionCode 15) — Accessibility Fix + Google Search + Notification Stop + Browser Sticking + BG Tasks
**Date:** 2026-10-12
**Commit:** `db27a6a`
**APK:** 43.8 MB

### Fixes
1. **Accessibility detection** — Robust 6-format matching (applicationId vs namespace vs class-only)
2. **Google Search App** — 4th search method option
3. **Notification Stop button** — TaskStopReceiver + notification action button
4. **Browser mode sticking** — lockedBrowserMethod prevents mixing in-app + Chrome
5. **Web Tools Guide** — System prompt explains webSearch vs browserOpen vs openInChrome
6. **Background Tasks page** — New screen with running task + Stop + recent activity

---

## v2.2.1 (versionCode 14) — Step Messages Saved + Browser AI/User Mode
**Date:** 2026-10-12
**Commit:** `738dfe4`
**APK:** 43.2 MB

### Fixes
1. **Step messages saved to DB** — addAiStepMessage() now calls database.addConversationToSession() with role='ai_step'
2. **Browser AI/User mode** — Top bar with mode indicator + "← Chat" button + user takeover detection
3. **Browser black/white screen** — Set white background on root layout

---

## v2.2.0 (versionCode 13) — Web Crash Fix + Chat Sessions + Accessibility + Task Banner
**Date:** 2026-10-12
**Commit:** `a2cb096`
**APK:** 43.2 MB

### Fixes
1. **BrowserActivity crash** — Added android:theme to manifest
2. **Accessibility status** — Use isEnabled(context) instead of isRunning()
3. **Task banner overlap** — Moved inside LazyColumn as first scrollable item
4. **Chat Session Management** — Added chat_sessions table (DB v4) + session_id column + 6 new DB methods + ChatViewModel loads history on init + "New Chat" button + Hub → History shows sessions

---

## v2.1.2 (versionCode 12) — Proxy Direct to Port 3030
**Date:** 2026-10-11
**Commit:** `a868e50`
**APK:** 43.2 MB

### Fix
- Z.ai Proxy endpoint URL updated with ?XTransformPort=3030 to bypass unstable port 3000

---

## v2.1.1 (versionCode 11) — Gemini 429 + Cline 402 + Usage Monitor
**Date:** 2026-10-11
**Commit:** `db16d5f`
**APK:** 39.4 MB

### Fixes
1. **Gemini 429** — Detect daily vs per-minute, wait appropriately
2. **Cline 402** — Handle insufficient credits explicitly
3. **BrowserActivity in manifest** — Was missing, caused crash
4. **Usage Monitor** — New Hub section with active provider + today stats + session + task + full report + provider quotas

---

## v2.1.0 (versionCode 10) — Cline Provider
**Date:** 2026-10-11
**Commit:** `170b77b`
**APK:** 43.2 MB

### New
- Cline provider: 13 models across 6 providers (OpenAI, Anthropic, Google, DeepSeek, Meta, Mistral)
- Endpoint: https://api.cline.bot/api/v1/chat/completions
- Model format: provider/model (e.g., openai/gpt-4o)

---

## v2.0.9 (versionCode 9) — Plugin/Skill Inject + Notif Prompt + Timeout + Markdown + Prompt Editor
**Date:** 2026-10-11
**Commit:** `b628655`
**APK:** 43.2 MB

### New
1. **PluginManager** wired into ChatViewModel
2. **SkillManager** wired + auto-trigger on user message match
3. **Notification Access** in onboarding (5th permission row)
4. **Timeout system** — 30s/step, 3min/task
5. **Markdown rendering** — **bold**, *italic*, `code` in AI messages
6. **System Prompt Editor** — 4 modes (Custom editable + Smart/Balanced/Fast read-only) + Restore Original + live token estimate

---

## v2.0.8 (versionCode 8) — Remove Browser from Bottom Nav
**Date:** 2026-10-11
**Commit:** `4000c7e`
**APK:** 43.1 MB

### Change
- Bottom nav: 2 tabs (Chat + Aira Lab). Browser accessible from chat top bar.

---

## v2.0.7 (versionCode 7) — Header Cleanup + Overlay Toggle + Tools Expand + Tagline
**Date:** 2026-10-11
**Commit:** `b2f02a7`
**APK:** 43.1 MB

### Fixes
1. Removed logo from chat header
2. Removed Hub icon, added Browser icon
3. Brought back tagline ("Your intelligent companion")
4. Overlay toggle in Settings (FLOATING OVERLAY group)
5. Fixed overlay button duplicating on every launch (static sIsVisible flag)
6. Tools category cards now expandable (tap to toggle)

---

## v2.0.6 (versionCode 6) — Rebrand to Aira
**Date:** 2026-10-11
**Commit:** `7560924`
**APK:** 43.1 MB

### Rebrand
- App name: "Aira" (was "AI Agent v2")
- Subtitle: "Personal AI Assistant"
- Tagline: "Your intelligent companion for seamless device control and daily tasks."
- New app icon: Aira logo PNG (1512×1512)
- Splash screen: "Aira" + subtitle + version
- Hub renamed to "Aira Lab"
- Removed Settings tab from bottom nav (accessible inside Aira Lab)

---

## v2.0.5 (versionCode 5) — All v1 Services Restored
**Date:** 2026-10-11
**Commit:** `1c31591`
**APK:** 39.4 MB

### Services
1. AgentService startup (foreground service)
2. AgentHttpServer on port 8080 (remote control)
3. RuleScheduler (36 triggers fire)
4. App folders auto-creation
5. Auto-permission check
6. Status orb / floating overlay
7. Voice command from overlay (already wired)

---

## v2.0.4 (versionCode 4) — New Skill Crash Fix + Built-in Edit/Delete + apikeys.in + TTS Toggle
**Date:** 2026-10-11
**Commit:** `21fb113`
**APK:** 39.3 MB

### Fixes
1. New Skill crash — Replaced BasicTextField with OutlinedTextField
2. Built-in skills Edit + Delete — Long-press shows View/Edit/Delete for all
3. apikeys.in provider — 5 models (GPT-4o, Claude, Gemini, GPT-3.5)
4. TTS toggle — Default OFF, VolumeUp/VolumeOff icon in chat header
5. Storage permission grant buttons in Hub → Permissions

---

## v2.0.3 (versionCode 3) — Skill Editor + Groq Models
**Date:** 2026-10-11
**Commit:** `c8a1b3d`
**APK:** 39.9 MB

### New
- SkillEditorScreen (View/Edit/Create modes + restore option)
- Long-press skill → View/Edit/Delete menu
- Groq models updated (GPT-OSS 120B, Qwen 3.8, MiniMax M2.7, Whisper ASR)
- Onboarding fix (has_completed_onboarding SharedPreferences flag)

---

## v2.0.2 (versionCode 2) — Splash + Onboarding + Provider Settings + Dark Mode
**Date:** 2026-10-11
**Commits:** `3640266`, `b628655`
**APK:** 39.3 MB

### New
1. Splash screen (emerald orb + fade-in + auto-navigate)
2. Onboarding (3 steps: Welcome → Permissions → Provider Setup)
3. Provider Settings screen (13 providers + Test button)
4. Animations (page transitions, button press, tool card expand)
5. Dark mode toggle (System/Light/Dark)
6. Memory management (search + edit + delete)
7. Knowledge upload
8. Skill creator from chat (auto-prompt after 3+ iterations)

---

## v2.0.1 (versionCode 1) — Voice + Attachments + 9 Hub Sections + Browser
**Date:** 2026-10-11
**APK:** 39.3 MB

### New
- Voice input (Google Voice Intent)
- File attachments (paperclip → GetContent → copy to attachments dir)
- 9 Hub sections functional (Skills, Rules, Tools, Plugins, Memory, Knowledge, History, Files, LLM, Vision, Audio, Permissions, Settings)
- In-app Browser WebView (BrowserScreen with URL bar + progress)

---

## v2.0.0 (versionCode 1) — Initial Scaffold
**Date:** 2026-10-11
**Commit:** `617e6af`
**APK:** 39 MB

### Foundation
- Jetpack Compose UI (Material 3)
- Custom theme (emerald accent #10B981, light mode default)
- Carry-over backend from v1 (LLMClient, AIProvider, ToolExecutor, Accessibility, DB, Skills, Rules)
- ChatViewModel with full agent loop (MAX_ITERATIONS=50, strategy switching, always learn)
- Bottom navigation (Chat/Browse/Hub)
- 14 LLM providers (Groq, Gemini, OpenRouter, Z.ai Direct, Z.ai Proxy, Cline, APIKeys.in, etc.)
