---
name: learn-topic
description: Teach the user about a topic (detailed explanation)
trigger: teach me|explain|what is|how does|learn about|tell me about|educate me
tools_used: [webSearch, fetchPageText, saveToKnowledge, remember, recallKnowledge]
version: 1.0
---
# Learn Topic

When the user asks to learn about something:

## Steps
1. recallKnowledge(topic) → check if already learned
2. webSearch("<topic> explained simply")
3. fetchPageText(top educational URL) → read content
4. Break down into simple explanation
5. saveToKnowledge(topic, explanation) → save for future
6. Reply with detailed but simple explanation
7. Ask: "Want to go deeper on any part?"

## Notes
- Use simple language (no jargon)
- Give examples
- Break into sections
- Save to knowledge for future reference
