# Aira v4.0.68 — Current Status

**Date:** November 1, 2026
**Version:** 4.0.68 (versionCode 196)
**APK:** `ai-agent-v4.0.68-debug.apk`

## What Works ✅

### Core Agent
- Full system prompt at ALL tiers (v4.0.64) — AI thinks, plans, follows instructions
- Honest reporting (v4.0.60) — no more "Done." lies
- Result analysis injection — AI sees which tools failed
- complete(success) verification — overrides false success if tools failed
- Malformed JSON fallback (v4.0.66) — regex extraction when Gemini returns broken JSON
- Sandbox vs terminal distinction (v4.0.65) — AI knows the difference
- TodoWrite (v4.0.62) — structured planning + progress tracking
- searchTools (v4.0.61) — search ALL tools by keyword

### Terminal System
- Multi-terminal support (v4.0.63) — connect to phone, tablet, laptop, cloud
- connectTerminal + runOnTerminal + listTerminals + disconnectTerminal
- Terminal Hub section (Hub → SYSTEM → Terminal)
- Terminal preview screen with live output (v4.0.64)
- Delete unwanted terminals (v4.0.64)
- Auto-prepend PATH in server (v4.0.68) — python3/pkg/node always in PATH
- PTY support, auto-Y prompts, ANSI stripping, cancellable HTTP calls
- Server listens on 0.0.0.0 (WiFi access for other devices)

### WhatsApp Auto-Reply
- Per-contact cooldown (v4.0.67) — Mom and Dad can both get replies
- Message queue (v4.0.67) — pending messages processed after cooldown
- Combine multiple messages (v4.0.67) — 3 messages from Mom → 1 combined reply
- Reply to specific sender (v4.0.67) — targets Mom's notification, not first available
- triggerValue normalization (v4.0.54) — "WhatsApp" → "com.whatsapp"
- WhatsApp Business support (v4.0.54) — "com.whatsapp.w4b"
- Action validation (v4.0.54) — rejects bare "replyToNotification" without args

### Model Support
- 15+ providers (Gemini, OpenRouter, Z.ai, Groq, Anthropic, etc.)
- 3 response formats (native tool_calls, JSON-in-content, Gemini format)
- Anthropic direct support (v4.0.59) — no gateway needed
- max_tokens fix (v4.0.58) — no more max_tokens=0 causing empty responses

## Known Issues ⚠️

| Issue | Impact | Workaround |
|-------|--------|------------|
| Token usage ~10-11k per call | High token consumption | Use Gemini free tier (1500 req/day) |
| Old "run_command" custom tool persists | AI might use it instead of runOnTerminal | Tell AI: "use runOnTerminal, not run_command" |
| Pillow can't pip install on AndroidIDE | Background removal fails | Need to install C deps (zlib, libjpeg) first |
| Prompt caching disabled | Every call re-sends full prompt | Could re-enable for providers that support it |
| idesetup can't run via run_command | Bash-only + interactive | User must run manually in AndroidIDE |

## Future Plans (not yet implemented)

### Token Optimization (planned for v4.0.69+)
- **Capability levels**: Start at Level 1 (~800 tokens), AI escalates to Level 2/3 when needed
- **escalateLevel tool**: AI decides when it needs more tools/instructions
- **Estimated savings**: ~56% on typical daily usage
- **Status**: Designed, not implemented — waiting for current version to stabilize

### Chat Screen Redesign (planned)
- Terminal status indicator in header
- TodoWrite progress display
- Cleaner icon layout
- Bottom navigation (Chat | Terminal | Hub)

### Visual Terminal (future)
- Option B: Embed terminal emulator in Aira
- Option C: Bundle Termux bootstrap (standalone)
- Current: Uses AndroidIDE-Rv2 terminal via HTTP

## How to Revert

If v4.0.68 has issues, revert to any previous version:
```bash
cd ai-agent-v2
git log --oneline  # find the version you want
git checkout <commit-hash> -- .
# Then rebuild
```

Or download an older APK from `/public/`:
- `ai-agent-v4.0.67-debug.apk` — before PATH auto-prepend
- `ai-agent-v4.0.66-debug.apk` — before WhatsApp multi-message
- `ai-agent-v4.0.64-debug.apk` — before regex JSON fallback
- `ai-agent-v4.0.60-debug.apk` — before terminal system

## Build History (v4.0.55 → v4.0.68)

| Version | Key Change | Commit |
|---------|-----------|--------|
| v4.0.55 | Fix false "Done." + reserve pattern_ prefixes + filter failed tools | f75b7de |
| v4.0.56 | Debug logging for GLM-5.3-Flash JSON parsing | af86f76 |
| v4.0.58 | FIX max_tokens=0 regression (root cause of parsing failures) | 3744e86 |
| v4.0.59 | Full direct Anthropic Claude support | 6a6d938 |
| v4.0.60 | HONEST AGENT — remove all "Done." lies + verify results | 7b8fd40 |
| v4.0.61 | searchTools — search ALL tools by keyword | 9f233c2 |
| v4.0.62 | TodoWrite — structured planning + progress tracking | f6ec1d9 |
| v4.0.63 | Multi-terminal support + Terminal Hub + preview screen | 1ff127a |
| v4.0.64 | Full system prompt at all tiers + delete terminals + live preview | 8c129a4 |
| v4.0.65 | Sandbox vs terminal distinction + runShellCommand warning | 8e171bc |
| v4.0.66 | Regex fallback for malformed JSON from Gemini | c334729 |
| v4.0.67 | WhatsApp multi-message: per-contact cooldown + queue + targeted reply | 26768ed |
| v4.0.68 | Auto-prepend PATH in terminal server | 8cf566e |
