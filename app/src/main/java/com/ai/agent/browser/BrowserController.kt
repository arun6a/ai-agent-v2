package com.ai.agent.browser

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import java.io.ByteArrayOutputStream
import com.ai.agent.ui.BrowserActivity

/**
 * Hybrid browser controller — supports visible in-app WebView + Chrome fallback.
 *
 * Visible WebView mode (default — v4.1.0+):
 *   - Uses BrowserActivity's visible WebView (user can see what AI does)
 *   - Full DOM access via JavaScript
 *   - Structured page reading (buttons, links, forms, inputs)
 *   - Smart form filling
 *   - Screenshots for VLM
 *   - Cookie persistence (user logs in once → AI has the session)
 *   - User can intervene when AI is stuck (mode toggle)
 *
 * Chrome fallback (for sites that need Chrome):
 *   - Uses openInChrome() to launch Chrome via Intent
 *   - Then uses accessibility tools (readScreen, clickByText, type) to control it
 *   - Requires accessibility service enabled
 *
 * Browser mode is controlled by AIProvider settings:
 *   - "in_app" → always use visible WebView
 *   - "chrome" → always use Chrome
 *   - "auto" → try in-app, fall back to Chrome for logged-in sites
 */
object BrowserController {

    private const val TAG = "BrowserController"
    private var webView: WebView? = null  // background fallback (if BrowserActivity not open)
    private var handler = Handler(Looper.getMainLooper())
    private var pageLoadDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Get the active WebView — ONLY the visible BrowserActivity's WebView.
     * v5.0.8: Removed the hidden background WebView fallback — it was causing the
     * "invisible browser" bug where the AI worked on a page the user couldn't see.
     * Now, if BrowserActivity isn't open, browser tools return an error telling the
     * AI to call browserOpen first (which brings the browser to foreground).
     */
    private fun getWebView(context: Context): WebView? {
        // ONLY use the visible WebView from BrowserActivity
        return BrowserActivity.activeWebView
    }

    /**
     * Open a URL — brings BrowserActivity to foreground with the URL.
     * v5.0.9: If the same URL is already loaded, don't reload — just bring to foreground.
     * This prevents the AI from opening the same website multiple times.
     * Uses SINGLE_TOP + REORDER_TO_FRONT so existing instance is reused.
     */
    fun openUrl(context: Context, url: String) {
        Log.i(TAG, "Opening URL (visible): $url")
        val finalUrl = if (!url.startsWith("http")) "https://$url" else url

        // v5.0.9: If browser is already open with the same URL, just bring to foreground
        val currentUrl = BrowserActivity.activeWebView?.url
        if (currentUrl != null && currentUrl.contains(finalUrl.removePrefix("https://").removePrefix("http://").take(30))) {
            Log.i(TAG, "Same URL already loaded — just bringing browser to foreground, no reload")
            BrowserActivity.launch(context, finalUrl)
            return
        }

        pageLoadDeferred = CompletableDeferred()
        // Launch BrowserActivity (reuses existing instance via SINGLE_TOP)
        BrowserActivity.launch(context, finalUrl)

        // Wait for page to load (15s timeout)
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(15000) { pageLoadDeferred?.await() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Page load timeout for $url — continuing anyway")
        }
    }

    /**
     * Create a background WebView (fallback when BrowserActivity isn't open).
     * This is used only when the user hasn't opened the browser screen yet.
     */
    private fun ensureBackgroundWebView(context: Context): WebView {
        if (webView != null) return webView!!
        handler.post {
            webView = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.i(TAG, "Page loaded: $url")
                        pageLoadDeferred?.complete(true)
                    }
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        Log.i(TAG, "Page loading: $url")
                    }
                }
            }
        }
        Thread.sleep(500)
        return webView!!
    }

    // ==================== BASIC NAVIGATION ====================

    fun goBack(context: Context) {
        val wv = getWebView(context) ?: return
        handler.post { if (wv.canGoBack()) wv.goBack() }
    }

    fun getCurrentUrl(context: Context): String? {
        val wv = getWebView(context) ?: return null
        return try {
            kotlinx.coroutines.runBlocking {
                val deferred = CompletableDeferred<String?>()
                handler.post { deferred.complete(wv.url) }
                kotlinx.coroutines.withTimeoutOrNull(2000) { deferred.await() }
            }
        } catch (e: Exception) { null }
    }

    // ==================== RAW PAGE TEXT ====================

    fun getPageText(context: Context): String? {
        val wv = getWebView(context) ?: return null
        val deferred = CompletableDeferred<String?>()
        handler.post {
            wv.evaluateJavascript("document.body ? document.body.innerText : 'empty'") { result ->
                val text = result?.let {
                    it.trim('"').replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
                }
                deferred.complete(text)
            }
        }
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(5000) { deferred.await() }
            }
        } catch (e: Exception) { null }
    }

    // ==================== STRUCTURED PAGE READING ====================

    /**
     * Returns a JSON string with structured page data:
     * { title, url, headings, links[], buttons[], inputs[], forms[], text }
     *
     * This is the key method that makes the browser agent actually useful —
     * the LLM sees page STRUCTURE, not just raw text.
     */
    fun readStructured(context: Context): String {
        val wv = getWebView(context); if (wv == null) return "{\"error\": \"No page loaded. Call browserOpen first.\"}"
        val deferred = CompletableDeferred<String>()
        val script = """
            (function() {
                function esc(s) { return (s||'').replace(/["\\]/g, ' ').replace(/\n/g, ' ').replace(/\s+/g, ' ').trim().substring(0, 200); }
                var result = {
                    title: document.title || '',
                    url: window.location.href,
                    headings: [],
                    links: [],
                    buttons: [],
                    inputs: [],
                    forms: [],
                    text: (document.body ? document.body.innerText : '').substring(0, 1000)
                };
                // Headings (h1-h3 only, to keep output manageable)
                var hs = document.querySelectorAll('h1, h2, h3');
                for (var i = 0; i < hs.length && i < 20; i++) {
                    result.headings.push({tag: hs[i].tagName.toLowerCase(), text: esc(hs[i].innerText)});
                }
                // Links (visible ones only)
                var ls = document.querySelectorAll('a[href]');
                for (var i = 0; i < ls.length && i < 30; i++) {
                    var rect = ls[i].getBoundingClientRect();
                    if (rect.width > 0 && rect.height > 0) {
                        result.links.push({text: esc(ls[i].innerText), href: ls[i].href.substring(0, 200)});
                    }
                }
                // Buttons (button elements + role=button + input[type=submit/button])
                var bs = document.querySelectorAll('button, [role="button"], input[type="submit"], input[type="button"]');
                for (var i = 0; i < bs.length && i < 20; i++) {
                    var rect = bs[i].getBoundingClientRect();
                    if (rect.width > 0 && rect.height > 0) {
                        var txt = esc(bs[i].innerText || bs[i].value || bs[i].getAttribute('aria-label') || '');
                        if (txt) result.buttons.push({text: txt, x: Math.round(rect.x), y: Math.round(rect.y)});
                    }
                }
                // Inputs (text, email, password, search, tel, url, number)
                var ins = document.querySelectorAll('input[type="text"], input[type="email"], input[type="password"], input[type="search"], input[type="tel"], input[type="url"], input[type="number"], input:not([type]), textarea, select');
                for (var i = 0; i < ins.length && i < 20; i++) {
                    var rect = ins[i].getBoundingClientRect();
                    if (rect.width > 0 && rect.height > 0) {
                        result.inputs.push({
                            type: ins[i].type || ins[i].tagName.toLowerCase(),
                            name: esc(ins[i].name || ins[i].id || ''),
                            placeholder: esc(ins[i].placeholder || ''),
                            label: esc(ins[i].getAttribute('aria-label') || ''),
                            value: esc(ins[i].value || ''),
                            x: Math.round(rect.x),
                            y: Math.round(rect.y)
                        });
                    }
                }
                // Forms
                var fs = document.querySelectorAll('form');
                for (var i = 0; i < fs.length && i < 10; i++) {
                    result.forms.push({action: esc(fs[i].action), method: fs[i].method || 'get', id: esc(fs[i].id || '')});
                }
                return JSON.stringify(result);
            })();
        """.trimIndent()
        handler.post {
            wv.evaluateJavascript(script) { result ->
                // Result is a JSON string wrapped in quotes (JS string)
                val cleaned = result?.let {
                    if (it.startsWith("\"") && it.endsWith("\"")) {
                        // Unescape the JSON string
                        it.substring(1, it.length - 1)
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\")
                            .replace("\\n", "\n")
                    } else it
                }
                deferred.complete(cleaned ?: "{}")
            }
        }
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(8000) { deferred.await() } ?: "{}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "readStructured error", e)
            "{\"error\": \"${e.message}\"}"
        }
    }

    /**
     * Returns all links on the page as JSON: [{text, href}, ...]
     */
    fun getLinks(context: Context): String {
        val wv = getWebView(context); if (wv == null) return "[]"
        val deferred = CompletableDeferred<String>()
        val script = """
            (function() {
                function esc(s) { return (s||'').replace(/["\\]/g, ' ').trim().substring(0, 150); }
                var links = [];
                var ls = document.querySelectorAll('a[href]');
                for (var i = 0; i < ls.length && i < 50; i++) {
                    var rect = ls[i].getBoundingClientRect();
                    if (rect.width > 0 && rect.height > 0) {
                        links.push({text: esc(ls[i].innerText), href: ls[i].href.substring(0, 200)});
                    }
                }
                return JSON.stringify(links);
            })();
        """.trimIndent()
        handler.post {
            wv.evaluateJavascript(script) { result ->
                val cleaned = result?.let {
                    if (it.startsWith("\"") && it.endsWith("\"")) {
                        it.substring(1, it.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                    } else it
                }
                deferred.complete(cleaned ?: "[]")
            }
        }
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(5000) { deferred.await() } ?: "[]"
            }
        } catch (e: Exception) { "[]" }
    }

    /**
     * Returns all form fields as JSON: [{type, name, placeholder, label, value, x, y}, ...]
     */
    fun getForms(context: Context): String {
        val wv = getWebView(context); if (wv == null) return "[]"
        val deferred = CompletableDeferred<String>()
        val script = """
            (function() {
                function esc(s) { return (s||'').replace(/["\\]/g, ' ').trim().substring(0, 100); }
                var inputs = [];
                var els = document.querySelectorAll('input, textarea, select');
                for (var i = 0; i < els.length && i < 30; i++) {
                    var rect = els[i].getBoundingClientRect();
                    if (rect.width > 0 && rect.height > 0) {
                        inputs.push({
                            tag: els[i].tagName.toLowerCase(),
                            type: els[i].type || '',
                            name: esc(els[i].name || els[i].id || ''),
                            placeholder: esc(els[i].placeholder || ''),
                            label: esc(els[i].getAttribute('aria-label') || ''),
                            value: esc(els[i].value || ''),
                            x: Math.round(rect.x),
                            y: Math.round(rect.y)
                        });
                    }
                }
                return JSON.stringify(inputs);
            })();
        """.trimIndent()
        handler.post {
            wv.evaluateJavascript(script) { result ->
                val cleaned = result?.let {
                    if (it.startsWith("\"") && it.endsWith("\"")) {
                        it.substring(1, it.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                    } else it
                }
                deferred.complete(cleaned ?: "[]")
            }
        }
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(5000) { deferred.await() } ?: "[]"
            }
        } catch (e: Exception) { "[]" }
    }

    // ==================== INTERACTION ====================

    /**
     * Fill a form field by matching its label, name, id, or placeholder.
     * Uses JavaScript to find and fill the field.
     * Returns true if a field was found and filled.
     */
    fun fillForm(context: Context, fieldSelector: String, value: String): Boolean {
        val wv = getWebView(context); if (wv == null) return false
        val deferred = CompletableDeferred<Boolean>()
        // Escape the value for safe JS embedding
        val escapedValue = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        val escapedSelector = fieldSelector.replace("\\", "\\\\").replace("'", "\\'")
        val script = """
            (function() {
                var selector = '$escapedSelector';
                var value = '$escapedValue';
                var els = document.querySelectorAll('input, textarea, select');
                var found = null;
                for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    var name = (el.name || el.id || '').toLowerCase();
                    var placeholder = (el.placeholder || '').toLowerCase();
                    var ariaLabel = (el.getAttribute('aria-label') || '').toLowerCase();
                    var labelText = '';
                    // Find associated label
                    if (el.id) {
                        var label = document.querySelector('label[for="' + el.id + '"]');
                        if (label) labelText = label.innerText.toLowerCase();
                    }
                    if (name === selector.toLowerCase() ||
                        placeholder.includes(selector.toLowerCase()) ||
                        ariaLabel.includes(selector.toLowerCase()) ||
                        labelText.includes(selector.toLowerCase()) ||
                        name.includes(selector.toLowerCase())) {
                        found = el;
                        break;
                    }
                }
                if (found) {
                    found.value = value;
                    found.dispatchEvent(new Event('input', {bubbles: true}));
                    found.dispatchEvent(new Event('change', {bubbles: true}));
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        handler.post {
            wv.evaluateJavascript(script) { result ->
                deferred.complete(result == "true")
            }
        }
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(3000) { deferred.await() } ?: false
            }
        } catch (e: Exception) { false }
    }

    /**
     * Click an element by CSS selector.
     * Returns true if clicked.
     */
    fun clickElement(context: Context, selector: String): Boolean {
        val wv = getWebView(context); if (wv == null) return false
        val deferred = CompletableDeferred<Boolean>()
        val escapedSelector = selector.replace("\\", "\\\\").replace("'", "\\'")
        val script = """
            (function() {
                var el = document.querySelector('$escapedSelector');
                if (el) {
                    el.click();
                    return true;
                }
                return false;
            })();
        """.trimIndent()
        handler.post {
            wv.evaluateJavascript(script) { result ->
                deferred.complete(result == "true")
            }
        }
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(3000) { deferred.await() } ?: false
            }
        } catch (e: Exception) { false }
    }

    /**
     * Click an element by visible text (more LLM-friendly than CSS selector).
     * Tries button, a, [role=button], and any element with matching text.
     */
    fun clickByText(context: Context, text: String): Boolean {
        val wv = getWebView(context); if (wv == null) return false
        val deferred = CompletableDeferred<Boolean>()
        val escapedText = text.replace("\\", "\\\\").replace("'", "\\'")
        val script = """
            (function() {
                var target = '$escapedText'.toLowerCase();
                var clicked = false;

                // Pass 1: Check buttons, links, and clickable elements with multiple text sources
                var els = document.querySelectorAll('button, a, [role="button"], input[type="submit"], input[type="button"], [onclick], .btn, .button, [class*="btn"], [class*="button"]');
                for (var i = 0; i < els.length; i++) {
                    var elText = (
                        els[i].innerText ||
                        els[i].textContent ||
                        els[i].value ||
                        els[i].getAttribute('aria-label') ||
                        els[i].getAttribute('title') ||
                        els[i].getAttribute('alt') ||
                        els[i].getAttribute('data-text') ||
                        els[i].getAttribute('data-label') ||
                        ''
                    ).toLowerCase();
                    if (elText.includes(target)) {
                        els[i].click();
                        clicked = true;
                        break;
                    }
                }

                // Pass 2: Check images (alt text)
                if (!clicked) {
                    var imgs = document.querySelectorAll('img, input[type="image"]');
                    for (var i = 0; i < imgs.length; i++) {
                        var altText = (imgs[i].getAttribute('alt') || imgs[i].getAttribute('title') || '').toLowerCase();
                        if (altText.includes(target)) {
                            imgs[i].click();
                            // Also try clicking parent (image might be inside a link)
                            var parent = imgs[i].parentElement;
                            while (parent && parent.tagName !== 'A' && parent.tagName !== 'BUTTON') {
                                parent = parent.parentElement;
                            }
                            if (parent) parent.click();
                            clicked = true;
                            break;
                        }
                    }
                }

                // Pass 3: Any element with matching text (leaf nodes)
                if (!clicked) {
                    var all = document.querySelectorAll('*');
                    for (var i = 0; i < all.length && i < 1000; i++) {
                        if (all[i].children.length === 0) {
                            var t = (
                                all[i].innerText ||
                                all[i].textContent ||
                                all[i].getAttribute('alt') ||
                                ''
                            ).toLowerCase();
                            if (t.includes(target)) {
                                all[i].click();
                                // Also click parent if child click didn't work
                                if (all[i].parentElement) {
                                    all[i].parentElement.click();
                                }
                                clicked = true;
                                break;
                            }
                        }
                    }
                }

                // Pass 4: Try iframes (cross-origin iframes can't be accessed, but same-origin can)
                if (!clicked) {
                    var frames = document.querySelectorAll('iframe');
                    for (var f = 0; f < frames.length; f++) {
                        try {
                            var frameDoc = frames[f].contentDocument || frames[f].contentWindow.document;
                            var frameEls = frameDoc.querySelectorAll('button, a, [role="button"], input[type="submit"]');
                            for (var i = 0; i < frameEls.length; i++) {
                                var elText = (frameEls[i].innerText || frameEls[i].textContent || '').toLowerCase();
                                if (elText.includes(target)) {
                                    frameEls[i].click();
                                    clicked = true;
                                    break;
                                }
                            }
                        } catch(e) {
                            // Cross-origin iframe — can't access
                        }
                        if (clicked) break;
                    }
                }

                return clicked;
            })();
        """.trimIndent()
        handler.post {
            wv.evaluateJavascript(script) { result ->
                deferred.complete(result == "true")
            }
        }
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(5000) { deferred.await() } ?: false
            }
        } catch (e: Exception) { false }
    }

    /**
     * List ALL clickable elements on the page with their text, tag, and href.
     * Used by the AI when browserClickText fails — to see what's actually clickable.
     */
    fun listClickableElements(context: Context): String {
        val wv = getWebView(context); if (wv == null) return "[]"
        val deferred = CompletableDeferred<String>()
        val script = """
            (function() {
                var results = [];
                var els = document.querySelectorAll('button, a, [role="button"], input[type="submit"], input[type="button"], [onclick], .btn, .button, [class*="btn"]');
                for (var i = 0; i < els.length && i < 50; i++) {
                    var el = els[i];
                    var text = (el.innerText || el.textContent || el.value || el.getAttribute('aria-label') || el.getAttribute('alt') || el.getAttribute('title') || '').trim();
                    if (text.length === 0) continue;
                    results.push({
                        tag: el.tagName.toLowerCase(),
                        text: text.substring(0, 100),
                        href: el.getAttribute('href') || '',
                        class: (el.className || '').toString().substring(0, 50),
                        type: el.getAttribute('type') || ''
                    });
                }
                return JSON.stringify(results);
            })();
        """.trimIndent()
        handler.post {
            wv.evaluateJavascript(script) { result ->
                deferred.complete(result ?: "[]")
            }
        }
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(3000) { deferred.await() } ?: "[]"
            }
        } catch (e: Exception) { "[]" }
    }

    /**
     * Get text from a specific CSS selector.
     */
    fun getText(context: Context, selector: String): String {
        val wv = getWebView(context); if (wv == null) return ""
        val deferred = CompletableDeferred<String>()
        val escapedSelector = selector.replace("\\", "\\\\").replace("'", "\\'")
        val script = "(function() { var el = document.querySelector('$escapedSelector'); return el ? el.innerText : ''; })();"
        handler.post {
            wv.evaluateJavascript(script) { result ->
                val text = result?.let {
                    if (it.startsWith("\"") && it.endsWith("\"")) {
                        it.substring(1, it.length - 1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
                    } else it
                }
                deferred.complete(text ?: "")
            }
        }
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(3000) { deferred.await() } ?: ""
            }
        } catch (e: Exception) { "" }
    }

    /**
     * Wait for an element to appear on the page (useful for SPAs).
     * Checks every 200ms for up to timeoutMs.
     * Returns true if element found, false if timeout.
     */
    fun waitForElement(context: Context, selector: String, timeoutMs: Long): Boolean {
        val wv = getWebView(context); if (wv == null) return false
        val escapedSelector = selector.replace("\\", "\\\\").replace("'", "\\'")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val deferred = CompletableDeferred<Boolean>()
            handler.post {
                wv.evaluateJavascript("(function() { return !!document.querySelector('$escapedSelector'); })();") { result ->
                    deferred.complete(result == "true")
                }
            }
            val found = try {
                kotlinx.coroutines.runBlocking {
                    kotlinx.coroutines.withTimeoutOrNull(1000) { deferred.await() } ?: false
                }
            } catch (e: Exception) { false }
            if (found) return true
            Thread.sleep(200)
        }
        return false
    }

    /**
     * Scroll down by N pixels.
     */
    fun scrollDown(context: Context, pixels: Int) {
        val wv = getWebView(context); if (wv == null) return
        handler.post {
            wv.evaluateJavascript("window.scrollBy(0, $pixels);", null)
        }
        Thread.sleep(500)
    }

    /**
     * Capture screenshot of the WebView as base64 JPEG.
     * Returns base64 string (no data: prefix), or null on failure.
     */
    fun screenshot(context: Context): String? {
        val wv = getWebView(context) ?: return null
        val deferred = CompletableDeferred<String?>()
        handler.post {
            try {
                val bitmap = Bitmap.createBitmap(
                    wv.width.coerceAtLeast(1),
                    wv.height.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                wv.draw(canvas)
                val stream = ByteArrayOutputStream()
                // Scale down if too large (keep under ~100K base64)
                val maxWidth = 480
                val scaled = if (bitmap.width > maxWidth) {
                    val ratio = maxWidth.toFloat() / bitmap.width
                    Bitmap.createScaledBitmap(bitmap, maxWidth, (bitmap.height * ratio).toInt(), true)
                } else bitmap
                scaled.compress(Bitmap.CompressFormat.JPEG, 60, stream)
                val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                bitmap.recycle()
                if (scaled !== bitmap) scaled.recycle()
                deferred.complete(base64)
            } catch (e: Exception) {
                Log.e(TAG, "Screenshot failed", e)
                deferred.complete(null)
            }
        }
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(5000) { deferred.await() }
            }
        } catch (e: Exception) { null }
    }

    /**
     * Execute arbitrary JavaScript on the page.
     */
    fun evalJs(context: Context, script: String): String? {
        val wv = getWebView(context) ?: return null
        val deferred = CompletableDeferred<String?>()
        handler.post {
            wv.evaluateJavascript(script) { result ->
                deferred.complete(result)
            }
        }
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(5000) { deferred.await() }
            }
        } catch (e: Exception) { null }
    }

    // ==================== CHROME FALLBACK ====================

    /**
     * Open a URL in Chrome (or default browser) via Intent.
     * Used as fallback for logged-in sites (Gmail, banking, social media).
     * After this, the agent uses accessibility tools (readScreen, clickByText, type)
     * to control Chrome — NOT the WebView methods above.
     */
    fun openInChrome(context: Context, url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                // Try Chrome specifically first
                setPackage("com.android.chrome")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // Chrome not installed — try default browser
            Log.w(TAG, "Chrome not available, trying default browser", e)
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "No browser available", e2)
                false
            }
        }
    }

    /**
     * Search Google in Chrome.
     */
    fun searchInChrome(context: Context, query: String): Boolean {
        val url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")
        return openInChrome(context, url)
    }

    fun destroy() {
        handler.post {
            webView?.destroy()
            webView = null
        }
    }
}
