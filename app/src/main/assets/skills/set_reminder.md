---
name: set-reminder
description: Set a reminder for a specific time
trigger: remind me|set reminder|don't let me forget|reminder at
tools_used: [createRule, remember, recall]
version: 1.0
---
# Set Reminder

When the user asks to set a reminder:

## Steps
1. Parse time from user message (e.g., "at 3pm", "in 2 hours", "tomorrow at 9am")
2. Parse action (what to remind about)
3. createRule(name="Reminder: <action>", triggerType="time", triggerValue="<HH:MM>", action="<action>")
4. If specific days → add days parameter
5. Reply: "Reminder set: <action> at <time>"

## Notes
- Default: daily reminder
- If "weekday" → days="mon,tue,wed,thu,fri"
- If "once" → use AlarmManager instead (future)
