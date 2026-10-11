---
name: check-price
description: Check the price of a product on Amazon and Flipkart
trigger: check price|price of|how much is|compare price|cheapest price
tools_used: [browserOpen, browserReadStructured, browserSearch, browserClickText, browserScrollDown]
version: 1.0
---

# Check Price

When the user asks to check the price of a product, follow these steps:

## Step 1: Search Amazon
1. Call `browserSearch("[product name] amazon")` to find the product on Amazon
2. Call `browserReadStructured()` to get page structure
3. Look for price in the page text or buttons (price is usually in ₹ format)
4. If you see a product link, call `browserClickText("[product name]")` to open it
5. Call `browserReadStructured()` again to get the product page with price
6. Extract the price and remember it

## Step 2: Search Flipkart (if asked to compare)
1. Call `browserSearch("[product name] flipkart")`
2. Call `browserReadStructured()` to get page structure
3. Look for price in the page text
4. If you see a product link, click it and read the page again
5. Extract the price and remember it

## Step 3: Compare and Report
- If only one price: "The price of [product] on [site] is [price]."
- If two prices: "[Product] is [price1] on Amazon and [price2] on Flipkart.
  [Amazon/Flipkart] is cheaper by [difference]."
- If price not found: "I couldn't find the price on [site]. The page might need login or the product might not be listed."

Rules:
- Prices are usually in ₹ (Indian Rupees) format
- Look for "Buy Now", "Add to Cart", or price text in the structured data
- Don't say "Done!" until you've actually found (or failed to find) the price
- If the page needs login, tell the user to log in in the browser screen
