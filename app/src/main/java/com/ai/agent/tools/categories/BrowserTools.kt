package com.ai.agent.tools.categories

import android.content.Context
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.browser.BrowserController
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.delay

/**
 * Browser tools category — extracted from ToolExecutor in v3.3.13.
 *
 * Tools: browserOpen, browserReadStructured, browserReadPage, browserGetLinks,
 * browserGetForms, browserFillForm, browserClickElement, browserClickText,
 * browserGetText, browserWaitForElement, browserScreenshot, browserScrollDown,
 * browserEval, browserBack, browserGetUrl, browserSearch, browserFill,
 * browserClick, browserListClickable, openInChrome, searchInChrome.
 *
 * Depends on BrowserController (untouched). browserScreenshot delegates the VLM
 * analysis step to VisionTools.analyzeScreenWithBase64VLM.
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object BrowserTools {

    private const val TAG = "BrowserTools"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            // === IN-APP BROWSER ===
            // In Chrome mode, browser tools (except browserOpen) don't work — they operate
            // on the in-app WebView, not Chrome. Auto-route to accessibility tools instead.
            "browserOpen" -> {
                val url = call.args["url"] as? String ?: return ToolExecutor.ToolResult(false, "missing url")
                val finalUrl = if (!url.startsWith("http")) "https://$url" else url
                val browserMode = AIProvider.getBrowserMode(context)
                when (browserMode) {
                    "chrome" -> {
                        // User chose Chrome mode — open in Chrome (needs accessibility)
                        val opened = BrowserController.openInChrome(context, finalUrl)
                        delay(3000)
                        if (opened) {
                            val screenText = try { service?.readScreen() ?: "(readScreen failed)" } catch (e: Exception) { "(readScreen error: ${e.message})" }
                            val finalScreen = if (screenText.length > 2000) screenText.take(2000) + "\n...[truncated]" else screenText
                            ToolExecutor.ToolResult(true, "Opened in Chrome: $finalUrl\n\nScreen content:\n$finalScreen\n\nNOTE: Chrome mode is active. Use accessibility tools (readScreen, tap, clickByText) to interact with Chrome. Do NOT use browserClickText/browserReadStructured — they operate on the in-app WebView, not Chrome.")
                        } else {
                            ToolExecutor.ToolResult(false, "Chrome not available")
                        }
                    }
                    // v2.4.0: "auto" + "in_app" + "embedded" all use BrowserScreen (Compose destination).
                    // NO Activity switch → MIUI never unbinds accessibility → a11y stays alive.
                    // BrowserController.openUrl() sets pendingUrl + triggers nav.navigate(Routes.BROWSER).
                    "auto", "in_app", "embedded" -> {
                        // This navigates to BrowserScreen + sets pendingUrl. BrowserScreen loads the URL.
                        BrowserController.openUrl(context, finalUrl)

                        // Wait for page to load (non-blocking, 10s timeout).
                        // BrowserScreen's onPageFinished completes pageLoadDeferred.
                        try {
                            kotlinx.coroutines.withTimeoutOrNull(10000) {
                                BrowserController.pageLoadDeferred?.await()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "BrowserScreen: page load timeout — continuing")
                        }

                        ToolExecutor.ToolResult(true, "Browser opened: $finalUrl — page loaded. Use browserReadStructured/browserClickText/browserFill to interact. The user can see the page and take over if needed (tap '⏸ Pause AI' in browser top bar).")
                    }
                    else -> {
                        // Default: BrowserScreen
                        BrowserController.openUrl(context, finalUrl)
                        delay(2000)
                        ToolExecutor.ToolResult(true, "Browser opened: $finalUrl — use browser tools to interact.")
                    }
                }
            }
            "browserReadPage" -> {
                val result = BrowserController.getPageText(context)
                ToolExecutor.ToolResult(true, if (result != null) result.take(3000) else "Browser not open or page not loaded")
            }
            "browserEval" -> {
                val js = call.args["script"] as? String ?: return ToolExecutor.ToolResult(false, "missing script")
                val result = BrowserController.evalJs(context, js)
                ToolExecutor.ToolResult(true, result ?: "No result")
            }
            "browserClick" -> {
                val selector = call.args["selector"] as? String ?: return ToolExecutor.ToolResult(false, "missing selector")
                val js = "document.querySelector('$selector')?.click(); 'clicked'"
                val result = BrowserController.evalJs(context, js)
                ToolExecutor.ToolResult(true, "Click attempted: $result")
            }
            "browserFill" -> {
                val selector = call.args["selector"] as? String ?: return ToolExecutor.ToolResult(false, "missing selector")
                val value = call.args["value"] as? String ?: return ToolExecutor.ToolResult(false, "missing value")
                // v2.3.7: First check if the element exists — fail clearly if not.
                // Was silently returning "filled" even when selector matched nothing.
                val checkJs = "document.querySelector('$selector') ? 'exists' : 'not_found'"
                val checkResult = BrowserController.evalJs(context, checkJs)
                if (checkResult?.contains("not_found") == true) {
                    ToolExecutor.ToolResult(false, "Element '$selector' not found on page. Use browserGetForms() to see actual form fields, or browserListClickable() to see clickable elements. Don't fill fields that don't exist.")
                } else {
                    val js = "document.querySelector('$selector').value = '$value'; 'filled'"
                    val result = BrowserController.evalJs(context, js)
                    ToolExecutor.ToolResult(true, "Filled '$selector' with '$value': $result")
                }
            }
            "browserScrollDown" -> {
                BrowserController.evalJs(context, "window.scrollBy(0, 800); 'scrolled'")
                ToolExecutor.ToolResult(true, "Scrolled down")
            }
            "browserBack" -> {
                BrowserController.goBack(context)
                delay(1000)
                ToolExecutor.ToolResult(true, "Went back")
            }
            "browserGetUrl" -> {
                val url = BrowserController.getCurrentUrl(context)
                ToolExecutor.ToolResult(true, url ?: "Browser not open")
            }
            "browserSearch" -> {
                val query = call.args["query"] as? String ?: return ToolExecutor.ToolResult(false, "missing query")
                val url = "https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                // v2.3.7: Use embedded WebView path (same as browserOpen) instead of BrowserActivity.
                // Was launching a separate Activity that didn't render — user saw blank screen.
                val browserMode = AIProvider.getBrowserMode(context)
                if (browserMode == "chrome") {
                    // Chrome mode — fall back to launching Chrome
                    val opened = BrowserController.openInChrome(context, url)
                    delay(3000)
                    if (opened) {
                        val screenText = try { service?.readScreen() ?: "(readScreen failed)" } catch (e: Exception) { "(error: ${e.message})" }
                        ToolExecutor.ToolResult(true, "Searched in Chrome: $query\n\nScreen content:\n${screenText.take(3000)}")
                    } else {
                        ToolExecutor.ToolResult(false, "Chrome not available")
                    }
                } else {
                    // v2.4.0: BrowserScreen — navigate + load URL
                    BrowserController.openUrl(context, url)
                    try {
                        kotlinx.coroutines.withTimeoutOrNull(10000) {
                            BrowserController.pageLoadDeferred?.await()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "browserSearch: page load timeout — continuing")
                    }
                    val pageText = BrowserController.getPageText(context)
                    val text = pageText?.take(3000) ?: "Page not loaded yet — try browserReadStructured to get current page content."
                    ToolExecutor.ToolResult(true, "Searched: $query (URL: $url)\n\nPage content:\n$text")
                }
            }
            // ==================== HYBRID BROWSER AGENT (v4.0.0) ====================
            "browserReadStructured" -> {
                val isChromeMode = AIProvider.getBrowserMode(context) == "chrome"
                if (isChromeMode) {
                    // Chrome mode — auto-fallback to readScreen (accessibility)
                    val screenText = try { service?.readScreen() ?: "(readScreen failed — accessibility not enabled)" } catch (e: Exception) { "(error: ${e.message})" }
                    val finalScreen = if (screenText.length > 2000) screenText.take(2000) + "\n...[truncated]" else screenText
                    ToolExecutor.ToolResult(true, "Chrome mode: reading Chrome screen via accessibility:\n$finalScreen")
                } else {
                    val result = BrowserController.readStructured(context)
                    ToolExecutor.ToolResult(true, "Structured page data:\n$result")
                }
            }
            "browserGetLinks" -> {
                val isChromeMode = AIProvider.getBrowserMode(context) == "chrome"
                if (isChromeMode) {
                    val screenText = try { service?.readScreen() ?: "(readScreen failed)" } catch (e: Exception) { "(error: ${e.message})" }
                    ToolExecutor.ToolResult(true, "Chrome mode: reading Chrome screen via accessibility:\n$screenText")
                } else {
                    val result = BrowserController.getLinks(context)
                    ToolExecutor.ToolResult(true, "Links on page:\n$result")
                }
            }
            "browserGetForms" -> {
                val result = BrowserController.getForms(context)
                ToolExecutor.ToolResult(true, "Form fields on page:\n$result")
            }
            "browserFillForm" -> {
                val field = call.args["field"] as? String ?: return ToolExecutor.ToolResult(false, "missing field (label, name, id, or placeholder)")
                val value = call.args["value"] as? String ?: return ToolExecutor.ToolResult(false, "missing value")
                val filled = BrowserController.fillForm(context, field, value)
                ToolExecutor.ToolResult(filled, if (filled) "Filled field '$field' with '$value'" else "Could not find field matching '$field'")
            }
            "browserClickElement" -> {
                val selector = call.args["selector"] as? String ?: return ToolExecutor.ToolResult(false, "missing selector (CSS)")
                val clicked = BrowserController.clickElement(context, selector)
                ToolExecutor.ToolResult(clicked, if (clicked) "Clicked: $selector" else "Element not found: $selector")
            }
            "browserClickText" -> {
                val text = call.args["text"] as? String ?: return ToolExecutor.ToolResult(false, "missing text")
                val isChromeMode = AIProvider.getBrowserMode(context) == "chrome"
                if (isChromeMode) {
                    // Chrome mode — use accessibility clickByText instead
                    val clicked = service?.clickByText(text) ?: false
                    if (clicked) {
                        delay(1500)  // let the page transition
                        val screenText = try { service?.readScreen() ?: "" } catch (e: Exception) { "" }
                        val finalScreen = if (screenText.length > 1000) screenText.take(1000) + "..." else screenText
                        ToolExecutor.ToolResult(true, "Chrome mode: clicked '$text' via accessibility. Screen after click:\n$finalScreen")
                    } else {
                        ToolExecutor.ToolResult(false, "Chrome mode: could not click '$text' via accessibility. Try readScreenStructured to see what's on screen, or tap(x,y) with coordinates.")
                    }
                } else {
                    val clicked = BrowserController.clickByText(context, text)
                    if (clicked) {
                        ToolExecutor.ToolResult(true, "Clicked: $text. Call browserReadStructured() or browserGetUrl() to see what happened next.")
                    } else {
                        ToolExecutor.ToolResult(false, "Text not found: '$text'. The text might be: (a) inside an image/iframe, (b) rendered differently, (c) page not fully loaded. Try: 1) browserWaitForElement('button', 3000) then retry, 2) browserReadStructured() to see ALL clickable elements + their text, 3) browserClickElement with a CSS selector like 'a[href*=\"product\"]', 4) browserEval('document.querySelectorAll(\"button, a\")') to list all buttons/links.")
                    }
                }
            }
            "browserListClickable" -> {
                val elements = BrowserController.listClickableElements(context)
                ToolExecutor.ToolResult(true, "Clickable elements on page:\n$elements")
            }
            "browserGetText" -> {
                val selector = call.args["selector"] as? String ?: return ToolExecutor.ToolResult(false, "missing selector (CSS)")
                val text = BrowserController.getText(context, selector)
                ToolExecutor.ToolResult(true, text.ifEmpty { "No text found for: $selector" })
            }
            "browserWaitForElement" -> {
                val selector = call.args["selector"] as? String ?: return ToolExecutor.ToolResult(false, "missing selector (CSS)")
                val timeout = (call.args["timeout"] as? Number)?.toLong() ?: 5000L
                val found = BrowserController.waitForElement(context, selector, timeout)
                ToolExecutor.ToolResult(found, if (found) "Element found: $selector" else "Timeout waiting for: $selector")
            }
            "browserScreenshot" -> {
                val base64 = BrowserController.screenshot(context)
                if (base64 != null) {
                    // Send to VLM for analysis
                    val prompt = call.args["prompt"] as? String ?: "Describe what's on this web page. Include any buttons, forms, prices, or important text."
                    val vlmResult = VisionTools.analyzeScreenWithBase64VLM(context, base64, prompt)
                    ToolExecutor.ToolResult(true, "Screenshot analyzed by VLM:\n$vlmResult")
                } else {
                    ToolExecutor.ToolResult(false, "Failed to capture screenshot")
                }
            }
            "browserScrollDown" -> {
                val pixels = (call.args["pixels"] as? Number)?.toInt() ?: 500
                BrowserController.scrollDown(context, pixels)
                ToolExecutor.ToolResult(true, "Scrolled down $pixels pixels")
            }
            "openInChrome" -> {
                val url = call.args["url"] as? String ?: return ToolExecutor.ToolResult(false, "missing url")
                val opened = BrowserController.openInChrome(context, url)
                ToolExecutor.ToolResult(opened, if (opened) "Opened in Chrome: $url — use readScreen() + clickByText() + type() to control Chrome" else "Chrome not available")
            }
            "searchInChrome" -> {
                val query = call.args["query"] as? String ?: return ToolExecutor.ToolResult(false, "missing query")
                val opened = BrowserController.searchInChrome(context, query)
                ToolExecutor.ToolResult(opened, if (opened) "Searched in Chrome: $query — use readScreen() to see results" else "Chrome not available")
            }
            else -> ToolExecutor.ToolResult(false, "Unknown browser tool: ${call.name}")
        }
    }
}
