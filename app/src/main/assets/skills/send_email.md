---
name: send-email
description: Send an email to a contact
trigger: send email|email to|compose email|mail to|write email
tools_used: [searchContacts, composeEmail, sendEmail, readScreen, type]
version: 1.0
---
# Send Email

When the user asks to send an email:

## Steps
1. If email address known → composeEmail(to, subject, body)
2. If only name known → searchContacts(name) → find email
3. Ask user for subject + body if not provided
4. composeEmail(email, subject, body)
5. Gmail/Email app opens with pre-filled content
6. Tell user: "Email ready to send. Tap send."

## Notes
- Don't auto-send (user should review)
- If no email app, use browserOpen("gmail.com")
