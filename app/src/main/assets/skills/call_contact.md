---
name: call-contact
description: Call a contact by name
trigger: call|phone|dial|ring|make a call
tools_used: [searchContacts, makePhoneCall, callContact, openDialer]
version: 1.0
---
# Call Contact

When the user asks to call someone:

## Steps
1. Parse contact name from message
2. searchContacts(name) → find phone number
3. makePhoneCall(number) or callContact(number)
4. Reply: "Calling <name>..."

## Notes
- If name not found → ask user for number
- If multiple matches → ask user to pick
- Always confirm before calling
