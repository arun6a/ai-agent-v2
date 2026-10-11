---
name: send-whatsapp
description: Send a WhatsApp message to a contact by name
trigger: send whatsapp|whatsapp message|message on whatsapp|send message to
tools_used: [searchContacts, openWhatsAppChat, sendSMS, waitAndContinue]
version: 1.0
---

# Send WhatsApp Message

When the user asks to send a WhatsApp message to someone, follow these steps:

## Step 1: Find the contact
1. Extract the contact name from the user's message (e.g., "send whatsapp to Mom" → "Mom")
2. Call `searchContacts("[name]")` to find their phone number
3. If no contact found, tell the user: "I couldn't find [name] in your contacts."

## Step 2: Extract the message
1. Extract the message content from the user's request
2. If no message specified, ask: "What should I say to [name]?"
3. If the user says "call pannu" or similar, the message is "call pannu"

## Step 3: Send via WhatsApp
1. Call `openWhatsAppChat("[phone number]", "[message]")` to open WhatsApp chat with the message
2. WhatsApp will open with the chat and message pre-filled
3. Tell the user: "I've opened WhatsApp chat with [name]. The message is ready to send."

## Step 4: Wait for reply (optional)
If the user also asked to be notified when the person replies:
1. Call `createRule(name="[name] Reply Watcher", triggerType="notification", triggerValue="com.whatsapp", action="[name] replied on WhatsApp. Read the notification and tell the user what they said.")`
2. Tell the user: "I'll notify you when [name] replies."

Rules:
- Phone numbers should include country code (e.g., +91 for India)
- If the contact has multiple numbers, use the first one
- Don't say "Done!" until WhatsApp has actually opened with the chat
- If WhatsApp is not installed, fall back to `sendSMS("[number]", "[message]")`
