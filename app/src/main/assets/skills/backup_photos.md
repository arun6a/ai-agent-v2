---
name: backup-photos
description: Backup photos to cloud storage
trigger: backup photos|backup my photos|upload photos|save photos to cloud
tools_used: [listFiles, shareFile, sendAttachmentTo, browserOpen, takePhoto]
version: 1.0
---
# Backup Photos

When the user asks to backup photos:

## Steps
1. listFiles("/storage/emulated/0/DCIM/Camera") → find photos
2. Ask user: "Backup to Google Drive, Gmail, or another app?"
3. If Google Drive → sendAttachmentTo("com.google.android.apps.docs", filename)
4. If Gmail → sendAttachmentTo("com.google.android.gm", filename)
5. If multiple photos → loop through each
6. Reply: "Backed up N photos to X"

## Notes
- Confirm before uploading (data usage)
- Process in background if many photos
