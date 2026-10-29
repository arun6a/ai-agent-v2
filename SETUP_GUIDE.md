# Aira — Setup Guide & Free API Keys

## Getting Free API Keys

Aira works out-of-the-box with OpenRouter's free tier, but adding free API keys from other providers gives you more reliable access + higher limits.

### 1. OpenRouter (already configured)
- **URL:** https://openrouter.ai/
- **Free tier:** Free models available (GLM-5.3-Flash, etc.)
- **Limit:** ~3192 tokens per request (prompt + completion)
- **Get key:** Sign up → Settings → Keys → Create Key
- **Note:** Add credits ($1-5) to increase token limits dramatically

### 2. Groq (RECOMMENDED — fast + generous free tier)
- **URL:** https://console.groq.com/
- **Free tier:** 14,400 requests/day, 30 requests/minute
- **Models:** Llama 3.3 70B, Mixtral, Gemma 2 — all fast + free
- **Get key:** Sign up → API Keys → Create
- **Add to Aira:** Hub → LLM → Configure → Provider: Groq → paste key

### 3. Brave Search API (for reliable web search)
- **URL:** https://api.search.brave.com/
- **Free tier:** 2000 queries/month (no credit card)
- **Get key:** Sign up → Subscribe to Free plan → Get API key
- **Add to Aira:** Hub → Settings → Search API Keys → Brave API Key

### 4. Google Custom Search API (official Google search)
- **URL:** https://developers.google.com/custom-search/v1/overview
- **Free tier:** 100 queries/day
- **Setup:**
  1. Create API key: Google Cloud Console → Enable Custom Search API → Create credentials
  2. Create Search Engine: https://programmablesearchengine.google.com/ → Get Engine ID (cx)
- **Add to Aira:** Hub → Settings → Search API Keys → Google API Key + Engine ID

### 5. Gemini (Google's free AI)
- **URL:** https://aistudio.google.com/
- **Free tier:** 15 requests/minute, 1500 requests/day
- **Models:** Gemini 2.5 Flash, Gemini 2.5 Pro
- **Get key:** Sign up → Get API key
- **Add to Aira:** Hub → LLM → Configure → Provider: Gemini → paste key

### 6. Together AI
- **URL:** https://api.together.xyz/
- **Free tier:** $5 free credits on signup
- **Models:** Llama 3.3, DeepSeek, Qwen
- **Get key:** Sign up → Settings → API Keys

### 7. Cerebras (very fast inference)
- **URL:** https://cerebras.ai/
- **Free tier:** Limited free access
- **Models:** Llama 3.1 8B (fastest inference)

---

## Recommended Setup (Best Free Experience)

1. **Primary:** OpenRouter with GLM-5.3-Flash (already configured)
   - Add $1-5 credits to increase token limits

2. **Fallback:** Groq (14,400/day free)
   - Add as a second binding in Hub → LLM → Configure
   - Auto-failover will switch to Groq if OpenRouter fails

3. **Search:** Brave Search API (2000/month free)
   - Add in Hub → Settings → Search API Keys
   - AI uses `braveSearch()` for reliable results

4. **Vision (optional):** Gemini 2.5 Flash
   - Configure as vision model in Hub → LLM → Vision
   - Fixes the VLM 401 error

---

## Permissions to Enable

After installing Aira, enable these permissions for full functionality:

### Required (app won't work without these)
1. **Accessibility Service** — Settings → Accessibility → AI Agent → ON
   - Required for screen reading, clicking, typing
2. **Notification Access** — Settings → Notification Access → AI Agent → ON
   - Required for WhatsApp auto-reply rules

### Recommended
3. **Storage (All Files Access)** — Hub → Permissions → Storage
   - Required for file operations (download, read, write)
4. **Battery Optimization Exemption** — Hub → Permissions → Battery
   - Prevents Android from killing Aira in background
5. **Display Over Other Apps** — Hub → Permissions → Overlay
   - Required for the floating Stop button

### Optional (for specific tools)
6. **Camera** — for takePhoto
7. **Location** — for getWeather, getCurrentLocation
8. **Contacts** — for readContacts, searchContacts
9. **SMS** — for readSms, sendSMS
10. **Phone** — for makePhoneCall, getPhoneState
11. **Calendar** — for getCalendarEvents

---

## Aira Working Directory

```
/storage/emulated/0/Aira/
  ├── attachments/    ← files you send to Aira
  ├── skills/          ← user-created skills (.md files)
  ├── knowledge/       ← saved knowledge documents
  ├── downloads/       ← downloaded files (images, PDFs, etc.)
  ├── screenshots/     ← screen captures
  └── .nomedia         ← prevents gallery indexing
```

---

## How to Use Aira Daily

### Basic Commands
- "hi" — greeting
- "what time is it" — getCurrentTime
- "check battery" — getBatteryLevel
- "search for latest AI news" — smartSearch (tries multiple engines)

### App Control
- "open YouTube" — launchApp
- "play BLACKPINK on YouTube" — searchInApp shortcut
- "send WhatsApp message to Mom" — openWhatsAppChat
- "search YouTube for cats" — searchInApp

### Rules (Automation)
- "every day at 7am, remind me to drink water" — createRule (time trigger)
- "when someone texts me on WhatsApp, auto-reply 'I'm busy'" — createRule (notification trigger)
- "when battery drops below 20%, vibrate twice" — createRule (battery_low trigger)
- "list all rules" — listRules
- "delete rule 1" — deleteRule

### Skills
- "list skills" — see available skills
- "create a skill for..." — createSkill

### Custom Tools
- "I need a tool to check my server" — createCustomTool (http handler)
- "list custom tools" — listCustomTools

### Memory
- "remember that my name is Arun" — remember
- "what do you remember about me?" — recallAll
- "get full memory" — getFullMemory (loads ALL memories)

### Search
- "search for X" — webSearch (Bing)
- "google search for X" — headlessGoogleSearch (WebView, no CAPTCHA)
- "brave search for X" — braveSearch (official API)

### Device Info
- "what's my battery health?" — getBatteryHealth
- "how much RAM is free?" — getMemoryInfo
- "how much storage left?" — getStorageInfo
- "is my phone charging?" — isCharging

### Navigation
- "open settings" — navigateToAppScreen(screen="settings")
- "open LLM settings" — navigateToAppScreen(screen="llm_settings")

---

## Troubleshooting

### "Insufficient credits" error
- Add credits at openrouter.ai/settings/credits (even $1 helps)
- OR add a second provider (Groq, Gemini) in Hub → LLM → Configure

### "Prompt too large" error
- Use simpler messages
- Aira auto-reduces tools + retries (should rarely happen after v4.0.33)

### Google search not working
- Google blocks HTTP scraping now (CAPTCHA)
- Use `headlessGoogleSearch` (WebView bypasses CAPTCHA)
- OR add Brave/Google API key in Settings → Search API Keys

### Accessibility "not enabled" when it IS
- Android killed the service. Toggle OFF then ON in Settings → Accessibility

### WhatsApp notification rules not firing
- Ensure Notification Access is enabled
- Open the app once (triggers `ensureConnected` rebind)

### VLM 401 error
- Vision model endpoint needs cookie auth
- Falls back to accessibility silently (app still works)
- Fix: configure a vision model (Gemini Flash) in Hub → LLM → Vision

---

## Build Commands

```bash
# Clone repo
cd /home/z/my-project && git clone https://github.com/arun6a/ai-agent-v2.git

# Restore JDK (if sandbox reset)
cd /tmp && curl -sL -o jdk17.tar.gz "https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.13%2B11/OpenJDK17U-jdk_x64_linux_hotspot_17.0.13_11.tar.gz" && mkdir -p /home/z/jdk17 && tar xzf jdk17.tar.gz -C /home/z/jdk17 --strip-components=1

# Restore Android SDK (if sandbox reset)
mkdir -p /home/z/android-sdk/cmdline-tools/latest && cd /tmp && curl -sL -o cmdline-tools.zip "https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip" && unzip -qo cmdline-tools.zip -d /tmp/sdk-extract && cp -r /tmp/sdk-extract/cmdline-tools/* /home/z/android-sdk/cmdline-tools/latest/ && yes | /home/z/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses && /home/z/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"

# Build
cd /home/z/my-project/ai-agent-v2 && echo "sdk.dir=/home/z/android-sdk" > local.properties && echo "org.gradle.jvmargs=-Xmx1500m -XX:MaxMetaspaceSize=384m" >> gradle.properties && JAVA_HOME=/home/z/jdk17 ANDROID_HOME=/home/z/android-sdk PATH=/home/z/jdk17/bin:$PATH bash gradlew assembleDebug --no-daemon --no-parallel

# Copy APK
cp app/build/outputs/apk/debug/app-debug.apk /home/z/my-project/public/ai-agent-v4.0.39-debug.apk

# Commit + push
git add -A && git commit -m "v4.0.XX: description" && git push origin main
```

---

## Download URLs

Latest APK:
```
https://preview-chat-1855dd56-e3a8-4ac4-a21c-824fbab8e552.space-z.ai/ai-agent-v4.0.39-debug.apk
```

Replace `v4.0.39` with any version number to download older builds.

---

## Current State (v4.0.39)

### Working Well
- ✅ Rules (time, notification, battery, charging triggers)
- ✅ Skills (built-in + user-created)
- ✅ Custom tools (AI creates at runtime)
- ✅ Pattern Learning (saves successful sequences)
- ✅ Device Calibration (learns click coordinates)
- ✅ Smart search (Bing + headless Google + Brave/Google APIs)
- ✅ Deep link navigation (AI opens app screens)
- ✅ Token budget management (auto-reduces to fit free tier)
- ✅ 402 retry (extracts affordable token count)
- ✅ WhatsApp auto-reply (no consent popup, instant send)
- ✅ Clean error messages (no provider recommendations)

### Known Issues
- ⚠️ VLM 401 (vision model endpoint needs cookie auth — a11y fallback works)
- ⚠️ OpenRouter free tier limited (3192 tokens — add credits or second provider)

### Future Plans (not now — after daily use + logs)
- UI/UX polishing
- Skill categories/grouping
- More built-in tools
- Better click accuracy (VLM fix)
- Conversation memory improvements
