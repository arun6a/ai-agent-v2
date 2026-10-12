---
name: translate-screen
description: Translate text on the current screen
trigger: translate screen|translate this|what language|translate to|what does this say
tools_used: [readScreen, translateText, readScreenStructured, tap]
version: 1.0
---
# Translate Screen

When the user asks to translate the screen:

## Steps
1. readScreen() → get all text on screen
2. Ask user: "What language to translate to?" (if not specified)
3. translateText(screenText, targetLanguage)
4. Reply with translated text
5. If user wants to replace → type(translatedText) into the field

## Notes
- Default target: English
- If screen has no text → try readScreenStructured or screenshot + VLM
