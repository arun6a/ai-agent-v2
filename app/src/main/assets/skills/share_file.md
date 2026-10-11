---
name: share-file
description: Share a file via WhatsApp, Email, or Bluetooth
trigger: share file|share this|send file|share with
tools_used: [listFiles, shareFile, sendAttachmentTo, readScreen, tap]
version: 1.0
---
# Share File

When the user asks to share a file:

## Steps
1. If file attached → use listAttachments() to find it
2. If path given → listFiles(path) to verify
3. Ask user: "Share via WhatsApp, Email, or Bluetooth?"
4. If WhatsApp → sendAttachmentTo("com.whatsapp", filename)
5. If Email → sendAttachmentTo("com.google.android.gm", filename)
6. If Bluetooth → shareFile(path, mimeType)
7. Tell user: "File shared via X"

## Notes
- Confirm before sending
- For images → set mimeType="image/jpeg"
