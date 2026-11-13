package com.ai.agent.tools.categories

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * v4.0.32: Headless WebView search + Brave Search API.
 *
 * TWO search approaches that bypass Google's CAPTCHA:
 *
 * 1. headlessGoogleSearch(query) — uses Android WebView (off-screen, no UI)
 *    to load Google search. WebView executes JavaScript, has cookies, looks
 *    like real Chrome → Google serves real results (no /sorry/ block).
 *    The WebView is created on the main thread (required by Android), loads
 *    the page, waits for JS to render, then extracts results via JS evaluation.
 *
 * 2. braveSearch(query) — official Brave Search API (2000 queries/month free).
 *    Returns clean JSON, no scraping. Requires API key in Settings.
 *    Get a free key at: https://api.search.brave.com/
 *
 * Also includes:
 * 3. smartSearch(query) — tries Bing HTTP → headless Google → Brave API
 *    in sequence, returning the first that yields results.
 */
object HeadlessSearchTools {
    private const val TAG = "HeadlessSearch"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "headlessGoogleSearch" -> {
                val query = call.args["query"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing query")
                headlessWebViewSearch(context, query, "google")
            }
            "headlessSearch" -> {
                val query = call.args["query"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing query")
                val engine = call.args["engine"] as? String ?: "google"
                headlessWebViewSearch(context, query, engine)
            }
            "braveSearch" -> {
                val query = call.args["query"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing query")
                val limit = (call.args["limit"] as? Number)?.toInt() ?: 5
                braveSearchApi(context, query, limit)
            }
            "googleSearchApi" -> {
                val query = call.args["query"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing query")
                val limit = (call.args["limit"] as? Number)?.toInt() ?: 5
                googleCustomSearchApi(context, query, limit)
            }
            "smartSearch" -> {
                val query = call.args["query"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing query")
                smartSearch(context, query)
            }
            else -> ToolExecutor.ToolResult(false, "Unknown search tool: ${call.name}")
        }
    }

    /**
     * Headless WebView search — loads a search engine in an off-screen WebView.
     *
     * The WebView must be created on the main thread (Android requirement).
     * We use a CountDownLatch to wait for the page to finish loading + JS to render.
     *
     * After the page loads, we inject JavaScript to extract result titles + URLs + snippets.
     */
    private suspend fun headlessWebViewSearch(
        context: Context,
        query: String,
        engine: String
    ): ToolExecutor.ToolResult {
        return withContext(Dispatchers.Main) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = when (engine.lowercase()) {
                    "google" -> "https://www.google.com/search?q=$encodedQuery"
                    "bing" -> "https://www.bing.com/search?q=$encodedQuery"
                    "duckduckgo" -> "https://duckduckgo.com/?q=$encodedQuery"
                    else -> "https://www.google.com/search?q=$encodedQuery"
                }

                Log.i(TAG, "Headless search: $engine → $searchUrl")

                val latch = CountDownLatch(1)
                var pageHtml = ""
                var loadError: String? = null

                // Create WebView on main thread (required by Android)
                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
                webView.settings.loadWithOverviewMode = true
                webView.settings.useWideViewPort = true

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.i(TAG, "Page finished loading: $url")
                        // Wait 2 seconds for JS to render results
                        Handler(Looper.getMainLooper()).postDelayed({
                            // Extract results via JavaScript
                            val js = """
                                (function() {
                                    var results = [];
                                    var selectors = [
                                        {sel: 'div.g h3', linkSel: 'div.g a', parent: 'div.g'},
                                        {sel: 'h3.LC20lb', linkSel: 'a', parent: 'div.g'},
                                        {sel: '.BNeawe.vvjwJb', linkSel: 'a', parent: '.kCrYT'},
                                    ];
                                    // Google mobile results
                                    var items = document.querySelectorAll('div.g, .kCrYT, .Gx5Zad');
                                    items.forEach(function(item, idx) {
                                        if (idx >= 8) return;
                                        var titleEl = item.querySelector('h3, .vvjwJb, .DKV0Md');
                                        var linkEl = item.querySelector('a[href]');
                                        var snippetEl = item.querySelector('.VwiC3b, .s3v9rd, .BNeawe.s3v9rd, [data-sncf]');
                                        if (titleEl && linkEl) {
                                            var title = titleEl.textContent.trim();
                                            var url = linkEl.href;
                                            var snippet = snippetEl ? snippetEl.textContent.trim() : '';
                                            if (title && url) {
                                                results.push({title: title, url: url, snippet: snippet});
                                            }
                                        }
                                    });
                                    // Fallback: Bing selectors
                                    if (results.length === 0) {
                                        var bingItems = document.querySelectorAll('li.b_algo');
                                        bingItems.forEach(function(item, idx) {
                                            if (idx >= 8) return;
                                            var titleEl = item.querySelector('h2 a, h2');
                                            var linkEl = item.querySelector('a[href]');
                                            var snippetEl = item.querySelector('p.b_lineclamp3, .b_caption p');
                                            if (titleEl && linkEl) {
                                                results.push({
                                                    title: titleEl.textContent.trim(),
                                                    url: linkEl.href,
                                                    snippet: snippetEl ? snippetEl.textContent.trim() : ''
                                                });
                                            }
                                        });
                                    }
                                    return JSON.stringify(results);
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(js) { result ->
                                pageHtml = result ?: ""
                                latch.countDown()
                            }
                        }, 2500) // 2.5s delay for JS rendering
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request?.isForMainFrame == true) {
                            loadError = "WebView error: ${error?.description}"
                            latch.countDown()
                        }
                    }
                }
                webView.webChromeClient = WebChromeClient()

                // Load the search URL
                webView.loadUrl(searchUrl)

                // Wait for results (max 15 seconds)
                val success = latch.await(15, TimeUnit.SECONDS)
                webView.destroy()

                if (!success) {
                    return@withContext ToolExecutor.ToolResult(false,
                        "Headless search timed out (15s). The page may not have finished loading.")
                }
                if (loadError != null) {
                    return@withContext ToolExecutor.ToolResult(false, loadError!!)
                }

                // Parse the JSON results
                val resultsStr = if (pageHtml.startsWith("\"")) {
                    // WebView wraps the result in quotes + escapes
                    org.json.JSONTokener(pageHtml).nextValue().toString()
                } else {
                    pageHtml
                }

                val results = try {
                    org.json.JSONArray(resultsStr)
                } catch (e: Exception) {
                    return@withContext ToolExecutor.ToolResult(false,
                        "Headless search returned no parseable results. Raw: ${pageHtml.take(200)}")
                }

                if (results.length() == 0) {
                    return@withContext ToolExecutor.ToolResult(true,
                        "No results found via headless $engine search for: $query. " +
                            "Try a different query or use braveSearch() for official API search.")
                }

                val sb = StringBuilder("Search results (headless $engine, ${results.length()} found):\n\n")
                for (i in 0 until results.length()) {
                    val item = results.optJSONObject(i) ?: continue
                    val title = item.optString("title", "")
                    val url = item.optString("url", "")
                    val snippet = item.optString("snippet", "")
                    sb.append("${i+1}. $title\n")
                    if (snippet.isNotEmpty()) sb.append("   $snippet\n")
                    sb.append("   $url\n\n")
                }
                ToolExecutor.ToolResult(true, sb.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Headless search failed", e)
                ToolExecutor.ToolResult(false, "Headless search error: ${e.message}")
            }
        }
    }

    /**
     * Brave Search API — official, 2000 queries/month free.
     * Returns clean JSON, no scraping.
     * Requires API key stored in SharedPreferences.
     */
    private suspend fun braveSearchApi(
        context: Context,
        query: String,
        limit: Int
    ): ToolExecutor.ToolResult {
        val apiKey = com.ai.agent.llm.AIProvider.getBraveSearchApiKey(context)
        if (apiKey.isEmpty()) {
            return ToolExecutor.ToolResult(false,
                "Brave Search API key not set. Get a free key (2000 queries/month) at " +
                    "https://api.search.brave.com/ then add it in Hub → Settings → Search.\n\n" +
                    "Alternatively, use headlessGoogleSearch(query) which doesn't need an API key.")
        }

        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = "https://api.search.brave.com/res/v1/web/search?q=$encodedQuery&count=$limit"
                Log.i(TAG, "Brave Search API: $query")

                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("X-Subscription-Token", apiKey)
                    .header("Accept", "application/json")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext ToolExecutor.ToolResult(false,
                        "Brave API error ${response.code}: ${body.take(200)}")
                }

                val json = org.json.JSONObject(body)
                val resultsArr = json.optJSONObject("web")?.optJSONArray("results")
                    ?: return@withContext ToolExecutor.ToolResult(true,
                        "No results from Brave for: $query")

                val sb = StringBuilder("Brave Search results (${resultsArr.length()}):\n\n")
                for (i in 0 until resultsArr.length()) {
                    val item = resultsArr.optJSONObject(i) ?: continue
                    val title = item.optString("title", "")
                    val url = item.optString("url", "")
                    val snippet = item.optString("description", "")
                    sb.append("${i+1}. $title\n")
                    if (snippet.isNotEmpty()) sb.append("   $snippet\n")
                    sb.append("   $url\n\n")
                }
                ToolExecutor.ToolResult(true, sb.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Brave search failed", e)
                ToolExecutor.ToolResult(false, "Brave search error: ${e.message}")
            }
        }
    }

    /**
     * Google Custom Search JSON API — official, 100 queries/day free.
     * Returns clean JSON, no scraping, no CAPTCHA.
     * Requires API key + Search Engine ID (cx) stored in SharedPreferences.
     *
     * Setup:
     * 1. Get API key: https://developers.google.com/custom-search/v1/overview
     * 2. Create Search Engine + get cx ID: https://programmablesearchengine.google.com/
     * 3. Add both in Hub → Settings → Search API Keys
     */
    private suspend fun googleCustomSearchApi(
        context: Context,
        query: String,
        limit: Int
    ): ToolExecutor.ToolResult {
        val apiKey = com.ai.agent.llm.AIProvider.getGoogleSearchApiKey(context)
        val engineId = com.ai.agent.llm.AIProvider.getGoogleSearchEngineId(context)
        if (apiKey.isEmpty() || engineId.isEmpty()) {
            return ToolExecutor.ToolResult(false,
                "Google Custom Search API key or Engine ID not set.\n" +
                    "Setup:\n" +
                    "1. Get API key: https://developers.google.com/custom-search/v1/overview\n" +
                    "2. Create Search Engine + get cx ID: https://programmablesearchengine.google.com/\n" +
                    "3. Add both in Hub → Settings → Search API Keys\n\n" +
                    "Alternatively, use headlessGoogleSearch(query) which doesn't need an API key.")
        }

        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = "https://www.googleapis.com/customsearch/v1?key=$apiKey&cx=$engineId&q=$encodedQuery&num=$limit"
                Log.i(TAG, "Google Custom Search API: $query")

                val client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return@withContext ToolExecutor.ToolResult(false,
                        "Google Custom Search API error ${response.code}: ${body.take(200)}")
                }

                val json = org.json.JSONObject(body)
                val items = json.optJSONArray("items")
                    ?: return@withContext ToolExecutor.ToolResult(true,
                        "No results from Google Custom Search for: $query")

                val sb = StringBuilder("Google Custom Search results (${items.length()}):\n\n")
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val title = item.optString("title", "")
                    val link = item.optString("link", "")
                    val snippet = item.optString("snippet", "")
                    sb.append("${i+1}. $title\n")
                    if (snippet.isNotEmpty()) sb.append("   $snippet\n")
                    sb.append("   $link\n\n")
                }
                ToolExecutor.ToolResult(true, sb.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Google Custom Search failed", e)
                ToolExecutor.ToolResult(false, "Google Custom Search error: ${e.message}")
            }
        }
    }

    /**
     * Smart search — tries multiple methods in sequence:
     * 1. Google Custom Search API (if key set) — best quality, official
     * 2. Brave Search API (if key set) — good quality, 2000/month free
     * 3. Bing HTTP scrape (fast, no UI, no key needed)
     * 4. Headless Google WebView (reliable, bypasses CAPTCHA, no key needed)
     *
     * Returns the first method that yields results.
     */
    private suspend fun smartSearch(
        context: Context,
        query: String
    ): ToolExecutor.ToolResult {
        Log.i(TAG, "Smart search: trying methods in sequence for '$query'")

        // Method 1: Google Custom Search API (if key set)
        val googleKey = com.ai.agent.llm.AIProvider.getGoogleSearchApiKey(context)
        val googleCx = com.ai.agent.llm.AIProvider.getGoogleSearchEngineId(context)
        if (googleKey.isNotEmpty() && googleCx.isNotEmpty()) {
            Log.i(TAG, "Smart search: trying Google Custom Search API")
            val result = googleCustomSearchApi(context, query, 5)
            if (result.success && !result.output.contains("No results")) {
                Log.i(TAG, "Smart search: Google Custom Search API succeeded")
                return ToolExecutor.ToolResult(true, "🔍 [Google API] ${result.output}")
            }
        }

        // Method 2: Brave API (if key set)
        val braveKey = com.ai.agent.llm.AIProvider.getBraveSearchApiKey(context)
        if (braveKey.isNotEmpty()) {
            Log.i(TAG, "Smart search: trying Brave Search API")
            val result = braveSearchApi(context, query, 5)
            if (result.success && !result.output.contains("No results")) {
                Log.i(TAG, "Smart search: Brave API succeeded")
                return ToolExecutor.ToolResult(true, "🔍 [Brave API] ${result.output}")
            }
        }

        // Method 3: Bing HTTP scrape (fast, no key)
        val bingResult = tryBingScrape(query)
        if (bingResult != null && bingResult.contains("1.")) {
            Log.i(TAG, "Smart search: Bing succeeded")
            return ToolExecutor.ToolResult(true, "🔍 [Bing] $bingResult")
        }
        Log.w(TAG, "Smart search: Bing failed, trying headless Google")

        // Method 4: Headless Google WebView (no key, bypasses CAPTCHA)
        val googleResult = headlessWebViewSearch(context, query, "google")
        if (googleResult.success && !googleResult.output.contains("No results")) {
            Log.i(TAG, "Smart search: Headless Google succeeded")
            return ToolExecutor.ToolResult(true, "🔍 [Headless Google] ${googleResult.output}")
        }

        return ToolExecutor.ToolResult(false,
            "All search methods failed for: $query\n" +
                "• Google API: ${if (googleKey.isEmpty()) "no key set" else "failed"}\n" +
                "• Brave API: ${if (braveKey.isEmpty()) "no key set" else "failed"}\n" +
                "• Bing: no results\n" +
                "• Headless Google: ${googleResult.output.take(100)}\n\n" +
                "Try: use browserOpen(https://google.com/search?q=...) to search in the visible browser.")
    }

    /**
     * Quick Bing HTTP scrape (used by smartSearch).
     */
    private fun tryBingScrape(query: String): String? {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://www.bing.com/search?q=$encodedQuery&setlang=en"
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val pattern = Regex(
                """<li class="b_algo">.*?<a[^>]*href="([^"]+)".*?<h2>(.*?)</h2>.*?<p class="b_lineclamp3">(.*?)</p>""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            )
            val sb = StringBuilder()
            var count = 0
            for (match in pattern.findAll(body).take(5)) {
                count++
                val resultUrl = match.groupValues[1]
                val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                val snippet = match.groupValues[3].replace(Regex("<[^>]+>"), "").trim()
                sb.append("$count. $title\n   $snippet\n   $resultUrl\n\n")
            }
            if (count == 0) null else sb.toString()
        } catch (e: Exception) {
            null
        }
    }
}
