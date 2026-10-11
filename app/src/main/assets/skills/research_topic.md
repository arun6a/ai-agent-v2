---
name: research-topic
description: Research a topic using multiple sources
trigger: research|find info about|tell me about|what is|explain|deep dive|investigate
tools_used: [webSearch, fetchPageText, saveToKnowledge, recallKnowledge, remember]
version: 1.0
---
# Research Topic

When the user asks to research a topic:

## Steps
1. recallKnowledge(query) → check if already researched
2. webSearch(query) → find top 3 results
3. fetchPageText(url1) → read first source
4. fetchPageText(url2) → read second source (optional)
5. Synthesize findings into summary
6. saveToKnowledge(topic, summary) → save for future reference
7. Reply with summary + sources

## Notes
- All background (no browser opens)
- Save findings to knowledge store
- Cite sources in reply
