---
name: play-youtube
description: Play a song or video on YouTube (search + tap first VIDEO result)
keywords: play, youtube, song, music, video, listen to
version: 1.1
---
# Play on YouTube

When the user asks to play a song, video, or music on YouTube, follow these EXACT steps in order. DO NOT SKIP STEPS.

## STEP 1: Search
Call `searchInApp("com.google.android.youtube", "<query>")` — opens YouTube + runs the search. Results appear on screen.

## STEP 2: Read the screen structure (DO NOT SKIP)
Call `readScreenStructured()` — returns a JSON tree of ALL elements on screen with their bounds.
- You MUST call this before tapping anything.
- Do NOT use clickByText on search results — YouTube shows CHANNELS mixed with VIDEOS, and clickByText will click the channel (which doesn't play anything).

## STEP 3: Find a VIDEO result (not a channel)
In the readScreenStructured JSON, look for elements that are VIDEOS:
- Videos have a duration like "3:45" or "4:20" next to them
- Videos have a thumbnail image
- Videos have titles like "BLACKPINK - 'How You Like That' M/V"

**WARNING: Do NOT tap a CHANNEL.** Channels say "Official Artist Channel" or "Subscribe" — they do NOT play music. If you tap a channel, you'll land on the channel page and nothing will play.

## STEP 4: Tap the video
Calculate the center of the video's bounds: `x = (left + right) / 2`, `y = (top + bottom) / 2`.
Call `tap(x, y)` — this plays the video.

## STEP 5: Verify
Call `readScreen()` — you should see video controls (pause button, progress bar) or a video is now playing.

---

## CRITICAL RULES (read these carefully)

1. **DO NOT re-search.** If `searchInApp` returned ✓, the results are on screen. Do NOT call searchInApp again with a different query. The results won't change.

2. **DO NOT clickByText on search results.** YouTube shows channels + videos mixed. clickByText("BLACKPINK") will click the CHANNEL, not a video. Use readScreenStructured + tap instead.

3. **DO NOT skip step 2.** You MUST call readScreenStructured before tapping. If you tap without reading the structure, you'll tap the wrong thing.

4. **DO NOT click "Official Artist Channel" or "Subscribe".** These are channel elements, not videos. They won't play music.

5. **DO NOT invent tools.** There is no "song" tool. Use `tap(x, y)` to play a video.

6. **DO NOT use out-of-bounds coordinates.** Screen is 1080x2400. If your tap x > 1080 or y > 2400, it's wrong — recalculate from readScreenStructured bounds.

## When NOT to use this skill
- "play X on Spotify" → use `playSpotify(query)` directly (1 call).
- "open YouTube" (no search) → use `launchApp("com.google.android.youtube")`.
