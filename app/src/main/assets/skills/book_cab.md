---
name: book-cab
description: Book a cab/ride via Ola or Uber
trigger: book a cab|book cab|call uber|call ola|book a ride|book taxi|i need a ride
tools_used: [launchApp, searchInApp, tap, type, readScreen, waitAndContinue]
version: 1.0
---
# Book Cab

When the user asks to book a cab:

## Steps
1. Check if Uber installed (listInstalledApps)
2. If Uber → launchApp("com.ubercab")
3. If Ola → launchApp("com.olacabs.customer")
4. Wait for app to load (waitAndContinue)
5. Type destination: type("<destination>")
6. Read screen to find ride options
7. Tap "Book" or "Confirm"
8. Tell user: "Cab booked. Driver details coming soon."

## Notes
- Always confirm destination with user
- Ask user which app they prefer (Uber/Ola)
