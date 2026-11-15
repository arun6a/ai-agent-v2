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

## Search Query Rule
- Search for the SONG TITLE + ARTIST, not verbs
- User said "play APT by rosé" → search for "APT by rosé and Bruno Mars" (not "Play by rosé")
- User said "play BLACKPINK" → search for "BLACKPINK" (not "play BLACKPINK")

## How to Find the Video (CRITICAL — read carefully)
- Call readScreenStructured() to get the element list with indices.
- Do NOT use readScreen() — the VLM misreads text (e.g., "APT." becomes "X7" or "XAT").
- readScreenStructured gives you the REAL text from the accessibility tree + each element's index.
- Find the first element that looks like a video result (has title text, usually clickable).
- Call clickElement(index=N) where N is that element's index.
- Do NOT use tap(x,y) with guessed coordinates — you will tap the search bar instead of the video.

## How to Verify Video is Playing (CRITICAL — read carefully)
After tapping a video result, call readScreen() to check the VLM description.

**Video IS playing if VLM shows:**
- A timestamp like "9:07" or "1:23" (current playback position)
- Title of the video (e.g., "ROSE & Bruno Mars - APT. (Official Music Video)")
- A channel name (e.g., "BLACKPINK")
- A view count (e.g., "212,016,100")

**Video is NOT playing if VLM shows:**
- A list of multiple videos (you're still on search results)
- A search bar with suggestions
- "This video is unavailable"

## How to Complete the Task (CRITICAL — read carefully)
When the video IS playing:
- Call complete(success=true, reason="Video playing: [title]. Saw [timestamp] + [view count] on screen.")
- Do NOT just say "Done!" with no tool calls — that is the OLD way.
- You MUST call complete(success=true, reason=...) to stop the loop.

When the video is NOT playing after 3 attempts:
- Call complete(success=false, reason="Could not find video. Last screen showed: [summary].")

## Scrolling
- If the first result is a "Mix" playlist or not the right video, scroll down to find the next one.
- You CAN scroll multiple times — the repeat cap does NOT block scrollDown.
- After each scroll, call readScreenStructured() to see the new results.
- Tap the first video with a duration field.
