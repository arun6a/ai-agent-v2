# Aira — Build History

Complete changelog for every build of Aira (AI Agent v2).

---

## v4.0.95 (versionCode 223) — Permission Hardening + 14 New Manifest Permissions
**Date:** 2026-11-10
**APK:** 42 MB (`ai-agent-v4.0.95-debug.apk`)
**Build:** succeeded in sandbox (Gradle 8.10.2 + JDK 21 + Android SDK 34)

### Theme
Turn silent `SecurityException` failures into actionable "go to Settings → X" error messages. Declare 14 permissions needed for future tools. No new features — purely defensive.

### 1. 14 New Permissions in AndroidManifest.xml

**Hardware control (for tools that already exist but couldn't run):**
- `TRANSMIT_IR` — `irSend()` on devices with IR blaster (Xiaomi/Redmi/Mi)
- `ANSWER_PHONE_CALLS` — future call-screening tool (Android 8+)
- `READ_PHONE_NUMBERS` — `readContacts` enrichment with phone numbers (Android 11+)
- `BODY_SENSORS` — heart rate, SpO2 sensors (future health tools)
- `ACTIVITY_RECOGNITION` — step counter, walking/cycling detection
- `HIGH_SAMPLING_RATE_SENSORS` — gyro/accel at >200Hz (future motion tools)
- `READ_BASIC_PHONE_STATE` — lightweight phone state read (Android 13+)

**Foreground service types (Android 14+ requirement — without them, AgentService crashes):**
- `FOREGROUND_SERVICE_CAMERA` — `takePhoto()`, future document scanner
- `FOREGROUND_SERVICE_MICROPHONE` — `VoiceManager` STT, future wake-word
- `FOREGROUND_SERVICE_LOCATION` — `getCurrentLocation()` background rules
- `FOREGROUND_SERVICE_BLUETOOTH` — BLE scanning rules
- `FOREGROUND_SERVICE_CONNECTED_DEVICE` — USB OTG, NFC

**Account & alarm:**
- `GET_ACCOUNTS` — future Gmail triage / Google account integration
- `USE_FULL_SCREEN_INTENT` — call/alarm notifications that take over the screen

### 2. `setBrightness` Pre-Checks WRITE_SETTINGS (DeviceTools.kt)

**Before:** `Settings.System.putInt()` threw `SecurityException` with a generic error.

**After:** Pre-checks `Settings.System.canWrite(context)` and returns early with actionable guidance:
```
Cannot change brightness — WRITE_SETTINGS not granted.
Go to Settings → Apps → Aira → Modify system settings → Allow.
On Xiaomi/MIUI: also check Security Center → Permissions → Aira → Modify settings
```
Plus a separate `SecurityException` catch with MIUI-specific path.

### 3. Bluetooth Permission Pre-Checks (HardwareTools.kt)

`getBluetoothState()` and `toggleBluetooth()` now pre-check `BLUETOOTH_CONNECT` on Android 12+ (API 31+) before accessing the adapter, returning:
```
BLUETOOTH_CONNECT permission not granted.
Go to Settings → Apps → Aira → Permissions → Nearby devices → Allow.
On Xiaomi/MIUI: also check Security Center → Permissions → Aira → Bluetooth
```

Bonus: `getBluetoothState()` now returns the device name + MAC address alongside the state — useful for the AI to reference the device by name.

### Pattern Established
```
1. Check permission via ContextCompat.checkSelfPermission() BEFORE the operation
2. If missing → return early with the exact permission name + standard Android path + MIUI path
3. Try the operation
4. Catch SecurityException separately → same MIUI hint
5. Catch Exception → generic fallback
```

### Files Changed
- `app/build.gradle.kts` — versionCode 222→223, versionName 4.0.94→4.0.95
- `AndroidManifest.xml` — 14 new permission declarations
- `tools/categories/DeviceTools.kt` — setBrightness pre-check + MIUI hint
- `tools/categories/HardwareTools.kt` — getBluetoothState + toggleBluetooth pre-checks + name/address return

### What v4.0.95 Does NOT Do
- ❌ Doesn't request permissions at runtime (only declares them — runtime requests happen in MainActivity on first launch)
- ❌ Doesn't apply the pre-check pattern to other tools (only setBrightness + 2 BT tools — candidates for v4.0.96: takePhoto, sendSMS, getCurrentLocation, readContacts, readSms)
- ❌ Doesn't solve the underlying MIUI permission issue (the Antigravity/ADB-client approach would — deferred)

---

## v4.0.94 (versionCode 222) — Native Function Calling for Gemini API
**Date:** 2026-11-10

### Root Cause Fix
Gemini 3.5 Flash Lite was returning `MALFORMED_FUNCTION_CALL` because `buildGeminiRequest()` never sent the tools array to the API. The model tried to call tools but couldn't format them correctly.

### Changes to LLMClient.kt
1. `buildGeminiRequest()` now accepts `toolFilter` parameter:
   - Builds Gemini-format tools array (`function_declarations`)
   - Includes built-in tools + custom tools + connector tools
   - Format: `tools: [{function_declarations: [{name, description, parameters}]}]`
2. `parseGeminiResponse()` now handles native `functionCall` objects:
   - Checks `parts[]` for `functionCall` objects (not just text)
   - Extracts name + args from `functionCall`
   - Returns structured `ToolCall` list (not manual JSON parsing)
   - Falls back to text/JSON parsing when no `functionCall` found
3. When tools are sent, system prompt is adapted (no JSON tool-call format instructions — Gemini handles it natively)

---

## v4.0.93 (versionCode 221) — Fix 7 Bugs from Log Analysis
**Date:** 2026-11-08

7 fixes based on real user log analysis (4381 lines):

1. **`launchApp` packageName alias** (AppTools.kt) — AI kept calling `launchApp(packageName=...)` but code only accepted `package=` or `pkg=`. Added `packageName` as a third alias → 90% of "missing package" errors fixed. Also fixed `getAppInfo`.
2. **`<queries>` in AndroidManifest** (Android 11+ package visibility) — Without `<queries>`, `getLaunchIntentForPackage()` returned null for unseen apps. Added `<intent>` query for MAIN+LAUNCHER → Aira can now see ALL launcher apps. Fixes "startActivity returned false" for apps not recently used.
3. **`listInstalledApps` shows ALL apps** — Removed `FLAG_SYSTEM` filter → YouTube, Settings, Clock now visible. Was causing false "not installed" conclusions for system/pre-installed apps. Apps now sorted alphabetically.
4. **`createConnector` handles `\/` escaped slashes** (ConnectorTools.kt)
5. **`connectConnector` validation** — checks saved connector exists before attempting OAuth
6. **`replyToNotification`** — handles WhatsApp's flat notification layout (was expecting RemoteInput only)
7. **Memory dedup** — FTS5 was returning duplicate rows for the same memory at different timestamps

---

## v4.0.92 (versionCode 220) — RAG Improvements
**Date:** 2026-11-07

3 RAG improvements (low-risk, high-impact):

1. **Unified `searchAll(query)` tool** (saves 3 API calls per search):
   - Searches ALL sources in ONE call: memories (FTS5) + knowledge files + tools + MCP servers
   - Runs all 4 searches in parallel (`coroutineScope + async`)
   - Returns top 3-5 per source, grouped, compact output
   - Added to `META_TOOLS` (available in on-demand mode)
   - Replaces the pattern of calling `recallMemory` + `searchKnowledge` + `searchTools` + `searchMcpServers` separately (4 iterations → 1)

2. **Auto-inject memories in on-demand mode** (was disabled):
   - Previously: `!useOnDemand` disabled memory injection for on-demand mode
   - Now: memory inject is ENABLED in on-demand mode (limited to 3 memories, ~150 tokens)
   - In normal mode: still uses 10 memories (full context)
   - Fixes the issue where on-demand AI didn't recall past experiences

3. **Conversation compression ON by default**:
   - `USE_CONV_COMPRESSION` now defaults to `true`
   - When iterations > 5, replaces old conversation with compact summary + keeps recent 3 iterations in full

---

## v4.0.91 (versionCode 219) — Diagnostic Logging for Gemini Empty Response
**Date:** 2026-11-06

**DIAGNOSTIC BUILD ONLY** — no behavior fixes. Adds detailed logging to identify WHY Gemini returned empty responses (the "Binding Google failed: " with no error detail issue).

### Changes to LLMClient.kt
1. `parseGeminiResponse()` now logs:
   - `promptFeedback.blockReason` (if prompt was blocked by safety filter)
   - `candidates[0].finishReason` (SAFETY, RECITATION, MAX_TOKENS, OTHER)
   - FULL response body (first 1000 chars) when content is empty
   - Returns descriptive error: `"Gemini returned empty: <reason>"` instead of empty string
2. `chatWithFailover` binding failure log now shows:
   - Full reply (was truncated to 80 chars, hiding the error reason)
   - Tool call count
   - `"Binding Google failed — full reply: <actual reply>"`

---

## v4.0.90 (versionCode 218) — Rules in level0/level2 + Connector Over-Researching Fix
**Date:** 2026-11-06

Two fixes:

1. **Rules Engine missing from level0.txt + level2.txt**:
   - Both prompts had ZERO mentions of `createRule`
   - AI at those levels didn't know Rules existed
   - User saying "remind me every morning at 8am" at Level 2 → AI couldn't create a rule
   - Added concise Rules section to both: when to create (triggers), `createRule` signature, `triggerType` options, DIRECT vs SMART actions
   - `system_prompt_smart.txt` already had the full Rules section (lines 242-287)

2. **Connector over-researching** (from user logs):
   - AI spent 7 iterations searching (webSearch, searchTools, recallSimilar, listKnowledge) before calling `createConnector` — burned the daily quota
   - Root cause: tool description said "Discovers endpoints from docs/web search"
   - Fix: clear "WHEN to search vs create directly" guidance in all 3 prompts + tool schema
     - Common APIs (Gmail, GitHub, etc.) → create DIRECTLY, no search
     - Custom/unknown APIs → webSearch docs first, then create
   - Updated tool schema description to match

---

## v4.0.89 (versionCode 217) — RFC 9728 MCP OAuth Discovery + Known Providers
**Date:** 2026-11-06

### MCP OAuth Enhancements
- Implemented RFC 9728 (Protected Resource Metadata) discovery for MCP servers
- `McpClient` now follows `WWW-Authenticate: Bearer resource_metadata="..."` → fetches metadata → finds authorization_servers → performs OAuth
- Added a built-in registry of known MCP providers with their OAuth endpoints
- Auto-discovers OAuth flow when connecting to authenticated MCP servers

---

## v4.0.88 (versionCode 216) — AI-Created Connectors with OAuth + Response Length Caps
**Date:** 2026-11-05

Three commits (26e5e3a, a2e86da, 15b2e05) + one revert (094624d):

### 1. AI-Created Connectors with OAuth (a2e86da)
Connector system — the AI builds integrations at runtime:
- `ConnectorStore` — data class + JSON persistence (modeled on `CustomToolStore`)
- `ConnectorExecutor` — OAuth 2.0 + PKCE flow + API calls + token refresh
- `ConnectorTools` — `createConnector` / `listConnectors` / `deleteConnector` / `connectConnector`
- `aira://callback` deep link in AndroidManifest + `OAuthCallbackActivity`
- Connector endpoint tools (`name__endpoint`) auto-injected in `ToolSchemas` + `ToolExecutor`
- Minimal teacher-style system prompt (not hardcoded APIs)

The AI discovers API endpoints from web docs and creates connectors with full OAuth support — no pre-registration needed. Each endpoint becomes a callable tool. Three auth types: `none`, `apikey`, `oauth_pkce`.

### 2. Connector Prompt in All Levels (26e5e3a)
Previously only `system_prompt_smart.txt` (Level 3) had the connector section. Now `level0.txt` (on-demand) and `level2.txt` (standard default) also teach the AI about `createConnector`. Level 1 (greeting) doesn't need it — too simple.

### 3. Response Length Caps — Let AI Choose (15b2e05)
`level0.txt` (on-demand) had "Your text reply should be BRIEF: 1 sentence max" which was too aggressive — it capped ALL responses, even explanations.

Changed to conditional guidance:
- When CALLING TOOLS: keep text brief (the action is the response)
- When ANSWERING A QUESTION: respond at whatever length fits the question

Same conditional guidance applied to `level2.txt`. `level1.txt` and `system_prompt_smart.txt` already let the AI choose freely.

### 4. Revert: Keep Length Caps as Designed (094624d)
The "1 sentence max" in `level0.txt` is intentional for on-demand mode (cheap/fast tool-call responses). The 3-layer intent detection (v4.0.73) handles the tool-vs-question decision BEFORE choosing the level:
- Layer 0: greeting → Level 1 (no tools, can be long)
- Layer 2: "how does"/"explain"/"difference between" → Level 1 (no tools)
- Layer 2: "send"/"open"/"play" (action verbs) → Level 2/3 (tools, brief)

So the length caps are correct per-level: Level 0 (1 sentence max, action mode), Level 1 (no cap), Level 2 (brief), Level 3 (very long allowed).

---

## v4.0.87 (versionCode 215) — Chat UI Improvements
**Date:** 2026-11-05
**APK:** 42 MB (`ai-agent-v4.0.87-debug.apk`)

### 5 Chat UX improvements

#### 1. Thinking + Tool Execution Cards — Better UX
- AI step messages now render as a unified **Step Card** with a clear header:
  `● Step 3/80 · executing · 5 calls ▶`
- Status dot colors by state: accent (executing, pulses), success (done), error (red)
- Thinking text is collapsible — collapsed shows a 2-line preview, expanded shows full reasoning
- Tool-call cards sit below the step card, each independently expandable (unchanged)
- Replaces the old generic "💭 Thinking" bubble with a structured, scannable layout

#### 2. "Stopping..." State
- When the user taps Stop, a clear **Stopping banner** appears at the top of the chat
  (`⏹ Stopping… — Finishing the current step`)
- `stopAgent()` no longer clears `isThinking`/`isExecuting` instantly, so the UI shows
  "stopping" instead of abruptly going idle — the banner stays until the loop's `finally`
  block winds everything down
- The TaskBanner is hidden while stopping (no redundant Stop button)

#### 3. Message Actions — Long press
- Long-press any message (user or AI) → **ModalBottomSheet** with:
  - 📋 **Copy** — copies message text to clipboard
  - 🔄 **Regenerate** — re-runs from the user message that produced this reply
  - 🗑️ **Delete** — removes the message from the chat
- Haptic feedback on long-press; sheet shows a preview snippet of the targeted message

#### 4. Proactive Suggestion Banner
- `ProactiveTickWorker` results now surface as an **inline banner** at the top of the chat
  (not just a notification) when the app is open
- New `ProactiveBus` singleton bridges the background `ProactiveAlarmReceiver` → the
  foreground `ChatViewModel` via a `StateFlow`
- Banner shows "✨ Aira suggests: {action}" + reason + **[Do it]** / **[Dismiss]**
- "Do it" sends the suggestion as a user message; "Dismiss" clears it

#### 5. Files Card — attached / created / file ops
- **Attached files** (user): staged attachments now render as file cards below the user
  bubble (📎 name + size + "attached" badge) — previously they were invisible in chat
- **File operations** (AI tools): `writeFile`, `copyFile`, `moveFile`, `deleteFile`,
  `createDirectory` results are extracted and rendered as visual cards:
  - 📄 created · 📋 copied · ↔️ moved · 🗑️ deleted · 📁 dir created
  - Each card shows the filename + a colored status badge

### Files changed
- `proactive/ProactiveBus.kt` (new) — StateFlow bridge for proactive suggestions
- `proactive/ProactiveScheduler.kt` — publishes Suggest/Execute to ProactiveBus
- `ui/screens/ChatViewModel.kt` — `ChatMessage.attachments`/`fileOps`, `FileOp` model,
  `ChatUiState.proactiveSuggestion`, `observeProactiveSuggestions()`, `deleteMessage()`,
  `regenerateFromMessage()`, `acceptProactiveSuggestion()`, `extractFileOps()`,
  `stopAgent()` fix, `sendMessage()` stores attachments
- `ui/components/ChatMessageItem.kt` — `StepCard`, `StatusDot`, `AttachedFileCard`,
  `FileOpCard`, `combinedClickable` long-press
- `ui/screens/ChatScreen.kt` — `StoppingBanner`, `ProactiveSuggestionBanner`,
  `MessageActionsSheet` (ModalBottomSheet), long-press wiring, banner layout

---

## v4.0.73 (versionCode 201) — Combined Approach + Universal Reasoning + Skill Fix
**Date:** 2026-11-03

### Problem
v4.0.72 replaced ContextualPromptBuilder with LevelManager (capability levels). But this was an EITHER/OR choice — both systems had value:
- **LevelManager** (budget): controls prompt size + tool count per level
- **ContextualPromptBuilder** (relevance): picks WHICH tools based on task keywords

Sending ALL 158 tools for "build apk" (Level 3) wasted tokens when only ~10 terminal tools were needed. Also: reasoning was disabled for all non-Z.ai providers, and skill files taught the AI to use the deprecated `run_command` tool.

### Solution — 5 changes in one release

#### 1. Combined Approach (LevelManager + ContextualPromptBuilder)
- New flag: `LevelManager.USE_COMBINED_APPROACH = true`
- New method: `LevelManager.classifyLevelWithContext(msg, groups)` — uses BOTH signals
- Level (1/2/3) controls the PROMPT TEMPLATE (level1.txt / level2.txt / system_prompt_smart.txt)
- Contextual groups control WHICH tools to send (capped by `getToolBudget(level)`)
- Example: "play BLACKPINK" → Level 2 prompt + Screen+Shortcuts+Apps tools (~30 tools, not 125)
- Example: "build apk" → Level 3 prompt + terminal tools only (~10 tools, not 158)
- Savings: 40-58% fewer tokens than either approach alone

#### 2. Universal Reasoning (ALL providers, not just Z.ai)
- `LLMClient.buildOpenAIRequest()` now sends `thinking: { type: "enabled" }` for ALL providers
- OpenAI o-series: sends `reasoning_effort: "medium"`
- OpenRouter/Cline reasoning models: sends `reasoning: { effort: "medium" }`
- Was: only `providerId == "zai" || "zai_direct"` got thinking
- Fix: model can now reason before acting on ALL providers (OpenRouter, Groq, OpenAI, etc.)

#### 3. Thinking-Compatible Prompt
- `adaptPromptForNativeTools()` updated — old prompt said "Put FULL reasoning in text reply"
- That caused DOUBLE reasoning: internal thinking + text narration → model stalled
- New prompt: "DO NOT narrate — DO IT by calling the tool. Narration without action = failure"
- Includes BAD vs GOOD examples to prevent the "narrate but don't act" bug

#### 4. Skill Files Fixed (run_command → runOnTerminal)
- `terminal_commands.md`: 30 `run_command(command="...")` → `runOnTerminal(name="...", command="...")`
- `setup_terminal_access.md`: 12 occurrences replaced + Step 3 (createCustomTool) removed
- Was: skills taught AI to use deprecated `run_command` → "unknown tool" errors + XML text output
- Fix: skills now teach the correct native `runOnTerminal(name, command)` format

#### 5. Escalation Expansion
- When AI calls `escalateLevel(3)`, ChatViewModel detects the level change
- Next iteration: tool filter expands to ALL Level 3 tools automatically
- Was: escalateLevel changed the level but didn't expand the tool filter
- Fix: AI gets the tools it needs immediately after escalation

### Files Changed
- `app/build.gradle.kts` — version 4.0.72 → 4.0.73, versionCode 200 → 201
- `app/src/main/java/com/ai/agent/llm/levels/LevelManager.kt` — added `USE_COMBINED_APPROACH`, `getToolBudget()`, `classifyLevelWithContext()`
- `app/src/main/java/com/ai/agent/llm/ContextualPromptBuilder.kt` — `getRelevantGroups()` made public, added `getAlwaysTools()` accessor
- `app/src/main/java/com/ai/agent/llm/LLMClient.kt` — thinking enabled for ALL providers + thinking-compatible prompt
- `app/src/main/java/com/ai/agent/ui/screens/ChatViewModel.kt` — combined prompt+tool assembly + escalation expansion + auto-expand in combined mode
- `app/src/main/java/com/ai/agent/tools/ToolExecutor.kt` — escalateLevel result includes tool expansion notice
- `app/src/main/assets/skills/terminal_commands.md` — 30 run_command → runOnTerminal
- `app/src/main/assets/skills/setup_terminal_access.md` — 12 run_command → runOnTerminal + removed createCustomTool step

### Backward Compatibility
- `USE_COMBINED_APPROACH = false` → falls back to v4.0.72 pure LevelManager behavior
- `USE_CAPABILITY_LEVELS = false` → falls back to v4.0.71 pure ContextualPromptBuilder behavior
- Both flags default to true (combined approach active)

---

## v4.0.51 (versionCode 179) — Cancellable HTTP Calls + Kill on Disconnect
**Date:** 2026-10-30

### Problem
With v4.0.50's infinite timeout, if a user sent a new message or tapped Stop during a long `run_command` (e.g., 5-min gradle build):
1. The agent loop was cancelled (`loopJob.cancel()`) ✓
2. BUT the OkHttp HTTP call kept blocking (not coroutine-aware) ✗
3. AND the server's subprocess kept running on the phone ✗
4. Result: orphaned processes consuming CPU until they finished on their own

### Fix — 3 layers of cancellation now propagate

#### 1. CustomTools.kt — Cancellable HTTP calls
- Changed `executeHttp` from `fun` → `suspend fun`
- Replaced blocking `client.newCall(req).execute()` with `suspendCancellableCoroutine` + `call.enqueue()`
- On coroutine cancellation: `call.cancel()` fires → OkHttp aborts the HTTP connection
- Added `CancellationException` handler → returns "⏹️ Command cancelled by user" instead of generic error
- Added imports: `Call`, `Callback`, `Response`, `suspendCancellableCoroutine`, `resume`, `resumeWithException`

#### 2. aira-server.js — Kill subprocess on client disconnect
- Added `runningProcs = new Set()` to track all active subprocesses
- Added `req.on('close', ...)` handler: when client disconnects (HTTP cancelled), kills `proc` with `SIGKILL`
- Registers/unregisters proc from `runningProcs` on start/close
- Added `"cancelled": true/false` field to response JSON
- Added `success: code === 0 && !timedOut && !clientDisconnected` (cancelled = not success)

#### 3. New /kill endpoint
- `GET /kill?token=aira` kills ALL running subprocesses
- Returns `{ success: true, killed: N, message: "All running commands killed" }`
- Useful for emergency stop or when AI detects user wants to cancel

### How cancellation flows now

```
User sends new message or taps Stop
  ↓
ChatViewModel: loopJob.cancel()
  ↓
CustomTools: suspendCancellableCoroutine invokeOnCancellation → call.cancel()
  ↓
OkHttp: closes HTTP connection
  ↓
aira-server.js: req.on('close') fires → proc.kill('SIGKILL')
  ↓
Phone: subprocess dies, CPU freed
```

**Result:** A 5-minute build is killed within ~1 second of the user tapping Stop.

### Response JSON additions
```json
{
  "success": false,
  "cancelled": true,           ← NEW v4.0.51
  "exitCode": null,
  "output": "partial output...",
  "elapsed": "3s",
  "pty": "script"
}
```

The AI sees `cancelled: true` and knows NOT to retry — the user moved on.

### Files Changed
- `app/build.gradle.kts` — version 4.0.50 → 4.0.51, versionCode 178 → 179
- `app/src/main/java/com/ai/agent/tools/categories/CustomTools.kt` — `executeHttp` suspend + cancellable
- `app/src/main/assets/skills/setup_terminal_access.md` — server: runningProcs Set, req.on('close'), /kill endpoint, version 4.0.51
- `app/src/main/assets/skills/terminal_commands.md` — document cancellation behavior + `cancelled` field

### Migration Notes
- **Server restart required**: User must restart the server after updating the skill
- **Recreate run_command tool**: Tell Aira "set up terminal access" to write the new server + recreate the tool
- **Backward compatible**: Old server + new APK works (just no kill-on-disconnect). New server + old APK works (just blocking HTTP call, not cancellable)

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
