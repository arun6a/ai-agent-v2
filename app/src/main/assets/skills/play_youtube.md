---
name: play-youtube
description: Play a song or video on YouTube (search + tap video result)
keywords: play, youtube, video, song, music, listen, watch
---
## YouTube Search Results Layout
- TOP of screen: search bar (y < 200) — has your typed query + suggestions
- BELOW search bar: video results (y > 300)
- Video results have: title + channel + DURATION (e.g., "2:56", "3:11")
- Channels say "Official Artist Channel" — DON'T tap these (no duration)
- To tap a video: find element with duration field in readScreenStructured → tap its bounds center

## If YouTube Opens to a Playing Video
- pressBack first → reach Home
- Then proceed with searchInApp

## First Result Rule
- For "play X" commands, tap the FIRST video result (the one with a duration)
- Don't ask "which one?" — just tap the first one
- After tapping, VERIFY: readScreen → does it show video controls (pause, progress bar)?

## Search Query Rule
- Search for the SONG TITLE + ARTIST, not verbs
- User said "play APT by rosé" → search for "APT by rosé and Bruno Mars" (not "Play by rosé")
- User said "play BLACKPINK" → search for "BLACKPINK" (not "play BLACKPINK")
