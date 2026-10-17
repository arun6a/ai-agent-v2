---
name: play-youtube
description: Play a song or video on YouTube (search + tap first result)
keywords: play, youtube, song, music, video, listen to
version: 1.0
---
# Play on YouTube

When the user asks to play a song, video, or music on YouTube, follow these steps:

## Steps
1. `searchInApp("com.google.android.youtube", "<query>")` — opens YouTube + runs the search. Results appear on screen.
2. `readScreenStructured()` — get the JSON tree of search results. Find the FIRST video result's bounds (format: "left,top,right,bottom").
3. `tap(center of bounds)` — actually plays the first video. Calculate center: x=(left+right)/2, y=(top+bottom)/2.
4. Verify: `readScreen()` should show video controls / a video is now playing.

## CRITICAL NOTES
- `searchInApp` shows search results but **does NOT play anything by itself**.
- You MUST follow up with `readScreenStructured` + `tap` to actually play a video.
- Do NOT re-search with a different query if `searchInApp` returned success — the results are already on screen. Tap one.
- Do NOT call `openInChrome` — YouTube is an app, not a website.
- Do NOT call `launchApp` first — `searchInApp` opens the app for you.

## When NOT to use this skill
- "play X on Spotify" → use `playSpotify(query)` directly (1 call).
- "open YouTube" (no search) → use `launchApp("com.google.android.youtube")`.
