---
name: read-news
description: Read the latest news headlines
trigger: read news|what's happening|latest news|news headlines|what's new
tools_used: [webSearch, fetchPageText, remember, getCurrentTime]
version: 1.0
---
# Read News

When the user asks to read news:

## Steps
1. webSearch("latest news today India") → find news sources
2. fetchPageText(top news URL) → read headlines
3. Summarize top 5 headlines
4. Reply: "Today's news: 1. ... 2. ... 3. ..."
5. saveToKnowledge("news_<date>", summary) → save for reference

## Notes
- Default: India news
- If user specifies topic → search that (e.g., "tech news")
- Keep summary short (5 headlines)
