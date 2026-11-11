# Aira — Test Results & QA Tracking

**Last updated:** 2026-11-12 (v4.0.99-collab3)
**Current APK:** `ai-agent-v4.0.99-debug.apk` (42 MB, versionCode 227)

---

## How to Run Tests

### Unit Tests (JVM — no device needed)
```bash
cd ai-agent-v2
./gradlew testDebugUnitTest
```
- Runtime: ~25-30 seconds
- No Android device required
- Tests live in `app/src/test/java/com/ai/agent/util/`
- HTML report: `app/build/reports/tests/testDebugUnitTest/index.html`

### Build APK
```bash
cd ai-agent-v2
export ANDROID_HOME=/home/z/android-sdk
export JAVA_HOME=/home/z/jdk-21
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

---

## Unit Test Results — v4.0.99

**Total: 458 tests, 0 failures, 0 errors, 0 skipped**

| Test File | Tests | Failures | Added By |
|-----------|-------|----------|----------|
| `JsonNormalizerTest.kt` | 24 | 0 | v4.0.99 (other AI) |
| `IntervalParserTest.kt` | 35 | 0 | v4.0.99 (other AI) |
| `PathResolverTest.kt` | 18 | 0 | v4.0.99 (other AI) |
| `RuleValidatorTest.kt` | 29 | 0 | v4.0.99 (other AI) |
| `ShellCommandValidatorTest.kt` | 48 | 0 | collab1 |
| `PatternKeyGeneratorTest.kt` | 38 | 0 | collab1 |
| `McpServerConfigTest.kt` | 45 | 0 | collab1 |
| `McpNamespaceParserTest.kt` | 36 | 0 | collab2 |
| `TierClassifierTest.kt` | 44 | 0 | collab2 |
| `RuleActionParserTest.kt` | 59 | 0 | collab3 |
| `ConnectorUrlBuilderTest.kt` | 52 | 0 | collab3 |
| `ConversationStoreTest.kt` | 30 | 0 | collab3 |

---

## Collaborator Test Sessions (collab1-collab3, total 458)

All pushed by the Collaborator AI (Z.ai). Pattern: extract pure logic from an
Android-bound class into `app/src/main/java/com/ai/agent/util/`, test it fully on
the JVM, document bugs found during extraction. **The extracted objects are
faithful copies — wiring the original classes to delegate to them is left to the
maintainer (each file lists its wiring GAPs in the header).**

### collab3 — 2026-11-12 — +141 tests (317 → 458)

New utilities + tests:

1. **RuleActionParser.kt** (59 tests) — extracted from `RuleEngine` (v3.0.0):
   `isDirectRule` classification, `stripPrefix`, `splitToolCalls`,
   `parseToolCall` (positional + named args, Int coercion, quote stripping).
   Bugs documented in header + asserted in tests:
   - **GAP-1 (HIGH):** args split on "," is not quote-aware —
     `speak("hello, world")` becomes TWO args ("hello", "world"). Corrupts any
     message containing a comma.
   - **GAP-2 (MEDIUM):** quoted numerics lose their type — `speak("50")` passes
     Int 50; short phone numbers become Ints, long ones stay String (type
     depends on the value, not the quoting).
   - **GAP-3 (LOW):** `foo( )` yields `{"0": ""}` instead of empty args.
   - **GAP-4 (LOW):** named-arg keys are not unquoted: `foo("key"=v)`.
   - **GAP-5 (LOW):** no DOTALL — newline inside parens → silently SMART rule.
   - **GAP-6 (INFO):** `tool:direct:foo(1)` is NOT direct (colon survives);
     `direct:tool:foo(1)` is.
   - **GAP-7 (MEDIUM):** removePrefix runs before trim — leading whitespace on
     `rule.action` breaks the `direct:` prefix and the rule silently degrades
     to a SMART rule (extra LLM call, needs internet).

2. **ConnectorUrlBuilder.kt** (52 tests) — extracted from `ConnectorExecutor`
   (v4.0.88): PKCE verifier/challenge/state, authorize URL, token request
   bodies, `{param}` path + body substitution, query-string building, apiBase
   joining, API-key header, 60s token-expiry skew guard.
   - Includes the **official RFC 7636 Appendix B test vector**
     (`dBjftJeZ…` → `E9Melhoa…`) — the S256 challenge implementation is proven
     spec-compliant.
   - **GAP-1 (HIGH):** `renderBodyTemplate` does not JSON-escape values —
     `He said "hi"` produces invalid JSON → connector APIs return 400.
   - **GAP-2 (MEDIUM):** leftover `{placeholders}` are never removed when an
     arg is missing — literal `{param}` is sent to the API.
   - **GAP-3 (MEDIUM):** path substitution uses URLEncoder (form encoding,
     space → `+`) instead of percent-encoding for path segments.
   - **GAP-4 (LOW):** authorize URL with an existing query gets a second `?`.
   - **GAP-5 (LOW):** empty `apiBase` → relative URL → OkHttp throws.
   - **GAP-6 (INFO):** query keys are not URL-encoded (values are).

3. **ConversationStoreTest.kt** (30 tests) — the agent loop's externalized
   memory (v4.0.76), tested DIRECTLY (already pure Kotlin, no extraction
   needed): add/get by step, 500/1000 truncations, the 80-entry cap (exact
   drop boundary), recent-window, progress summary, clear + snapshot safety,
   duplicate-step behavior. Quirks documented:
   - **QUIRK-1:** `toolCallsSummary` is NOT truncated (other fields are).
   - **QUIRK-2 (crash risk):** `getRecentIterations(-1)` throws
     IllegalArgumentException (Kotlin takeLast contract).
   - **QUIRK-3:** duplicate step numbers allowed; `getIteration` returns first.

### collab2 — 2026-11-11 — +80 tests (237 → 317)
- **McpNamespaceParser.kt** (36 tests) — from `McpManager.resolveTool`;
  `__`-in-server-name bug documented.
- **TierClassifier.kt** (44 tests) — from `ContextualPromptBuilder`; tier
  1/2/3 classification, greeting detection, medium-complexity heuristics.

### collab1 — 2026-11-11 — +131 tests (106 → 237)
- **ShellCommandValidator.kt** (48 tests) — from `CustomTools.kt`; 6 shell
  bypass gaps documented (`rm -r -f`, `find / -delete`, `dd of=`, `curl | sh`,
  `chmod -R 777 /data`, subdir rm).
- **PatternKeyGenerator.kt** (38 tests) — from `PatternLearner`; 40-char
  prefix collision bug documented.
- **McpServerConfigTest.kt** (45 tests) — isHealthy, isTokenExpired,
  namespacing, defaults.

### Not JVM-testable (device tests)
- **ConsentManager** — depends on ConsentOverlay (WindowManager/Looper) and
  `Settings.canDrawOverlays`. On the JVM the overlay object cannot initialize.
  Covered by tomorrow's device-test plan item #1 (show/hide race).

---

### Test Coverage by Area

#### JsonNormalizer (24 tests) — v4.0.96 fix
The `normalizeJsonValue` function that converts `JSONObject`→`Map` and `JSONArray`→`List`. This was the root cause of 7 failures in the v4.0.94 logs (todoWrite, createConnector, actionChain all failed because arrays arrived as JSONArray but tools expected String).

- Primitive types pass through unchanged (String, Int, Long, Double, Boolean)
- JSONObject → Map conversion
- JSONArray → List conversion
- Nested structures (todoWrite args, createConnector endpoints, actionChain)
- Null filtering (from objects and arrays)
- Edge cases (empty objects, nested empty, array of arrays)
- Real-world Gemini functionCall args

#### IntervalParser (35 tests) — v4.0.98 fix
The interval parser for `createRule(triggerType="interval")`. Had 3 failures in v4.0.97 logs (AI tried `*/30`, `30 minutes`, `every 30 minutes` before getting it right).

- With "every" prefix (original format)
- Without "every" prefix (v4.0.98 fix)
- Short forms (2h, 30m)
- Unit variations (hour/hr/hours, minute/min/mins)
- Case insensitivity
- `normalize()` method
- `toMillis()` method
- Invalid inputs (empty, cron syntax, garbage, zero)
- Real-world log inputs from v4.0.97 test run

#### PathResolver (18 tests) — v4.0.98 fix
Relative path auto-resolve for file tools. Had 3 failures in v4.0.97 logs (`writeFile(path="Aira/test.txt")` → ENOENT).

- Absolute paths returned as-is
- Relative paths resolved against workspace
- `/sdcard/` → `/storage/emulated/0/` normalization
- Null path returns workspace root
- Edge cases (empty string, dots, double dots)
- Real-world log inputs

#### RuleValidator (29 tests) — new in v4.0.99
Validates rule trigger types, time formats, and day names.

- Time format HH:MM validation (valid + invalid cases)
- Day name validation (mon, tue, wed, thu, fri, sat, sun)
- Trigger type validation (all 34 supported types)
- Case sensitivity
- Edge cases

---

## Device Test Results

### v4.0.97 Test Run (Xiaomi Pad 7) — 2026-11-11
- **Total tool calls:** 193
- **Successes:** 160 (83%)
- **Failures:** 33 (17%)
- **GEMINI EMPTY CONTENT warnings:** 0 (was 99 before v4.0.96 fix)
- **Log file:** `upload/logs.txt` (4974 lines)

### v4.0.97 Test Run #2 — 2026-11-11
- **Total tool calls:** 63
- **Successes:** 55 (87%)
- **Failures:** 8 (13%)
- **WiFi toggle incident:** Yes — Aira toggled WiFi off, agent died
- **Log file:** `upload/logs.txt` (520 lines, partial run)

### Bugs Found + Fixed in v4.0.96–v4.0.99

| Version | Bug | Fix | Tests |
|---------|-----|-----|-------|
| v4.0.96 | todoWrite rejects List args | normalizeJsonValue helper | 24 tests |
| v4.0.96 | createConnector rejects List endpoints | normalizeJsonValue helper | (above) |
| v4.0.96 | setAlarm Permission Denial | Fixed permission name | — |
| v4.0.96 | createCalendarEvent missing alias | start→startTime alias | — |
| v4.0.96 | createRule no interval type | Added interval triggerType | 35 tests |
| v4.0.96 | GEMINI EMPTY CONTENT false positive | Moved warning after functionCall check | — |
| v4.0.97 | Permission errors silent | Pre-check + MIUI hints for all tools | — |
| v4.0.98 | searchTools infinite loop | Loop detector after 3 repeats | — |
| v4.0.98 | Relative paths fail | PathResolver auto-resolve | 18 tests |
| v4.0.98 | Interval format strict | More lenient parser | 35 tests |
| v4.0.98 | getCallLog LIMIT SQL error | Removed LIMIT from sortOrder | — |
| v4.0.98 | type/submitInput on own UI | Fixed package check (com.ai.agent.v2) | — |
| v4.0.98 | safeToggleWifi kills agent | Warning before WiFi off | — |
| v4.0.99 | Sub-agent 5 iteration limit | Increased to 10/15/8 per type | — |
| v4.0.99 | createRule days rejects List | Accept String + List | — |
| v4.0.99 | Hardcoded paths | Use Config constants | — |

---

## Test Infrastructure

### Files Added
```
app/src/main/java/com/ai/agent/util/
  ├── JsonNormalizer.kt     — extracted from LLMClient (production)
  ├── IntervalParser.kt     — extracted from RuleTools (production)
  ├── PathResolver.kt       — extracted from FileTools (production)
  └── RuleValidator.kt      — extracted from RuleTools (production)

app/src/test/java/com/ai/agent/util/
  ├── JsonNormalizerTest.kt  — 24 tests
  ├── IntervalParserTest.kt  — 35 tests
  ├── PathResolverTest.kt    — 18 tests
  └── RuleValidatorTest.kt   — 29 tests
```

### Build Dependencies
```kotlin
// app/build.gradle.kts
testImplementation("junit:junit:4.13.2")
testImplementation("org.json:json:20240303")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
```

### Key Facts
- Tests do NOT increase APK size (testImplementation is compile-only)
- Tests run on JVM — no Android device needed
- Full test suite runs in ~25 seconds
- Pattern: extract pure logic → standalone object → test without Context

---

## For Other AIs — How to Contribute

### Adding New Tests
1. Find a function with bugs (check `upload/logs.txt` for `→ ✗` lines)
2. Extract the pure logic to a standalone object in `app/src/main/java/com/ai/agent/util/`
3. Write a test file in `app/src/test/java/com/ai/agent/util/`
4. Run `./gradlew testDebugUnitTest` to verify
5. Update this document with new test counts

### Reporting Device Test Results
1. Run the test suite on a real device
2. Share `logs.txt` (from `/sdcard/Aira/logs/` or equivalent)
3. Add a new section under "Device Test Results" above with:
   - Version tested
   - Device name
   - Total tool calls / successes / failures
   - Notable bugs found
   - Log file location

### Bug Report Template
```
### Bug: [short description]
- **Version:** v4.0.99
- **Tool:** toolName
- **Input:** toolName(param="value")
- **Expected:** [what should happen]
- **Actual:** [what happened]
- **Root cause:** [if known]
- **Fix:** [if known]
- **Test added:** [test file name or "pending"]
```

---

## Pending Tests (Not Yet Written)

| Function | File | Priority | Why |
|----------|------|----------|-----|
| Positional args normalizer | ToolExecutor.kt | High | `speak(0="text")` failed in logs |
| Permission checker | ToolExecutor.kt | Medium | v4.0.95/97 pre-checks |
| Tool name resolver | ToolExecutor.kt | Medium | Case-insensitive matching |
| ~~Rule action parser~~ ✅ DONE | ~~RuleEngine.kt~~ → RuleActionParser.kt | — | collab3 (59 tests) |
| ~~Connector URL/PKCE~~ ✅ DONE | ~~ConnectorExecutor.kt~~ → ConnectorUrlBuilder.kt | — | collab3 (52 tests) |
| ~~ConversationStore~~ ✅ DONE | ConversationStore.kt | — | collab3 (30 tests) |
| ConsentManager state machine | ConsentManager.kt | Medium | Needs JVM-safe refactor (overlay deps) |
| ConnectorStore JSON round-trip | ConnectorStore.kt | Medium | Needs Context-free JSON extraction |
| Memory FTS5 search | AgentDatabase.kt | Low | Hard to test without SQLite |

---

## Version History

| Version | Date | Tests | APK | Key Changes |
|---------|------|-------|-----|-------------|
| v4.0.94 | Nov 10 | 0 | — | Native function calling for Gemini |
| v4.0.95 | Nov 10 | 0 | 42 MB | Permission hardening + 14 new permissions |
| v4.0.96 | Nov 11 | 0 | 42 MB | normalizeJsonValue + 7 log fixes |
| v4.0.97 | Nov 11 | 0 | 42 MB | Permission pre-checks for all tools |
| v4.0.98 | Nov 11 | 0 | 42 MB | searchTools loop + safeToggleWifi + 10 fixes |
| v4.0.99 | Nov 11 | 106 | 42 MB | Sub-agent limit + days List + test infrastructure |
| v4.0.99-collab1 | Nov 11 | 237 | — | ShellCommandValidator + PatternKeyGenerator + MCP config tests |
| v4.0.99-collab2 | Nov 11 | 317 | — | McpNamespaceParser + TierClassifier tests |
| v4.0.99-collab3 | Nov 12 | 458 | — | RuleActionParser + ConnectorUrlBuilder + ConversationStore tests (13 new GAPs documented) |

---

**Maintained by:** Z.ai Code (primary) + other AIs (welcome to contribute)
**Update protocol:** After each test run or fix, update this document and commit to GitHub.
