---
name: scan-qr
description: Scan a QR code
trigger: scan qr|scan code|read qr|qr scanner
tools_used: [launchApp, runShellCommand, readScreen]
version: 1.0
---
# Scan QR

When the user asks to scan a QR code:

## Steps
1. Try launchApp("com.google.zxing.client.android") → Barcode Scanner
2. If not installed → use Google Lens: launchApp("com.google.android.apps.search.lens")
3. If neither → browserOpen("google.com/search?q=qr+scanner") → online scanner
4. Tell user: "Camera opened. Point at QR code."

## Notes
- Google Lens can scan QR codes
- If user needs URL from QR → ask them to scan + share result
