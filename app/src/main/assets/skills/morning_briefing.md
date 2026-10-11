---
name: morning-briefing
description: Give a morning briefing with weather, calendar, and battery status
trigger: morning briefing|good morning briefing|what's my morning|give me a briefing
tools_used: [getCalendarEvents, webSearch, getBatteryLevel, getCurrentTime, getCurrentLocation]
version: 1.0
---

# Morning Briefing

When the user asks for a morning briefing, follow these steps:

1. Call `getCurrentTime()` to know today's date and time
2. Call `getCalendarEvents()` to get upcoming events for today
3. Call `getBatteryLevel()` to check the phone's battery
4. Call `getCurrentLocation()` to get the user's location
5. Call `webSearch("weather today [location]")` to get weather info

Then give a brief spoken summary (2-3 sentences max):

"Good morning! Today is [day]. You have [N] events: [list top 2-3 events with times].
Weather is [condition], [temp]°. Battery is at [X]%."

Rules:
- Keep it SHORT — this is spoken aloud via TTS
- If no calendar events, skip that part
- If weather search fails, say "I couldn't check the weather"
- Always end with the battery level
