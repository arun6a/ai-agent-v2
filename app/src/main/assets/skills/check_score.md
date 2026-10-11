---
name: check-score
description: Check live sports scores
trigger: what's the score|match result|score|who won|live score|cricket score|football score
tools_used: [webSearch, fetchPageText, getCurrentTime]
version: 1.0
---
# Check Score

When the user asks for a score:

## Steps
1. Parse sport + teams from message
2. webSearch("<team1> vs <team2> score today")
3. fetchPageText(top result URL) → read score
4. Reply: "Score: <team1> X - Y <team2>. Status: <live/finished>"

## Notes
- Default: cricket (India)
- If no specific match → search "live cricket scores today"
- Keep reply short
