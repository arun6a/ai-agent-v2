---
name: pay-bill
description: Pay a bill via GPay or PhonePe
trigger: pay bill|pay my|pay electricity|pay water|pay gas|bill payment
tools_used: [launchApp, searchInApp, tap, type, readScreen, waitAndContinue]
version: 1.0
---
# Pay Bill

When the user asks to pay a bill:

## Steps
1. Ask user: "Pay via GPay, PhonePe, or Paytm?"
2. launchApp("<payment_app>")
3. Wait for app to load (waitAndContinue)
4. Tap "Bills" or "Payments" section
5. Select bill type (electricity/water/gas)
6. Enter account number if needed
7. Tap "Pay"
8. Ask user: "Confirm payment of ₹X?"

## Notes
- Always confirm before payment
- Don't store payment details
- If app not installed → suggest install
