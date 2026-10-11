---
name: order-food
description: Order food from Zomato or Swiggy
trigger: order food|order X from zomato|order X from swiggy|hungry|order lunch|order dinner
tools_used: [searchInApp, readScreenStructured, tap, type, browserOpen, browserClickText, browserReadStructured]
version: 1.0
---
# Order Food

When the user asks to order food:

## Steps
1. Check if Zomato installed (listInstalledApps)
2. If Zomato → searchInApp("com.application.zomato", query)
3. If Swiggy → searchInApp("in.swiggy.android", query)
4. If neither → browserOpen("zomato.com") + browserReadStructured
5. Find the dish → tap to open restaurant
6. Add to cart → tap "Add" or "Add to Cart"
7. Go to checkout → tap "Checkout" or "View Cart"
8. Ask user: "Should I place the order? Total: ₹X"

## Notes
- Always confirm before placing order (payment involved)
- Remember user's preferred app: remember("food_app", "zomato" or "swiggy")
