---
name: daily-summary
description: Summarize the user's day (calls, messages, calendar, battery)
trigger: summarize my day|how was my day|daily summary|end of day|day recap
tools_used: [getCallLog, getCalendarEvents, getBatteryLevel, getCurrentTime, recallAll, recallKnowledge]
version: 1.0
---
# Daily Summary

When the user asks for a daily summary:

## Steps
1. getCurrentTime() → check current time
2. getCallLog() → recent calls
3. getCalendarEvents() → today's events
4. getBatteryLevel() → battery status
5. recallAll() → check memories from today
6. Summarize: "Today you had N calls, N meetings. Battery at X%. You remembered: ..."
7. Reply with summary

## Notes
- Keep it short (max 5 sentences)
- Focus on important events
- Include battery if low
