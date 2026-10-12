---
name: play-music
description: Play music on Spotify or YouTube
trigger: play music|play song|listen to|play X on spotify|play X on youtube
tools_used: [playSpotify, searchInApp, readScreenStructured, tap, readScreen]
version: 1.0
---
# Play Music

When the user asks to play music:

## Steps
1. Check if Spotify is installed (listInstalledApps)
2. If Spotify installed → use playSpotify(query) — 1 call, auto-plays
3. If not installed → use searchInApp("com.google.android.youtube", query) → readScreenStructured → tap(first video)

## Notes
- Spotify is preferred (audio only, no video)
- YouTube is fallback (video plays)
- Remember the user's preference: remember("music_preference", "spotify" or "youtube")
