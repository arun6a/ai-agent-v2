---
name: check-traffic
description: Check traffic to a destination
trigger: check traffic|how's traffic|traffic to|route to|drive time
tools_used: [getCurrentLocation, openMapsLocation, webSearch]
version: 1.0
---
# Check Traffic

When the user asks to check traffic:

## Steps
1. Parse destination from message
2. getCurrentLocation() → find user's location
3. openMapsLocation("<destination>") → open Google Maps
4. Maps shows traffic + route
5. Reply: "Opened Maps with route to <destination>. Check traffic details."

## Notes
- Requires location permission
- If location fails → just openMapsLocation(destination)
