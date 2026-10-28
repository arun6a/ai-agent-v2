package com.ai.agent.tools.categories

import android.content.Context
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Web tools category — extracted from ToolExecutor in v3.3.13.
 *
 * Tools: webSearch, makeHttpRequest, downloadFile, fetchPageText.
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object WebTools {

    private const val TAG = "WebTools"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            // === HTTP REQUESTS (Web Research) ===
            "makeHttpRequest" -> {
                val url = call.args["url"] as? String ?: return ToolExecutor.ToolResult(false, "missing url")
                val method = call.args["method"] as? String ?: "GET"
                try {
                    // v2.2.7: Wrap in withContext(IO) — was blocking main thread → ANR
                    val (respCode, respBody) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val request = okhttp3.Request.Builder().url(url).build()
                        val response = client.newCall(request).execute()
                        response.code to (response.body?.string() ?: "")
                    }
                    val truncated = if (respBody.length > 2000) respBody.take(2000) + "\n[...truncated]" else respBody
                    ToolExecutor.ToolResult(true, "HTTP $respCode:\n$truncated")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "HTTP error: ${e.message}")
                }
            }
            // v5.0.8: Background page reader — fetches URL, strips HTML, returns clean text.
            // NO browser opens. Use for RESEARCH tasks (reading content) — user stays on chat.
            // For INTERACTION tasks (clicking, forms, ordering) use browserOpen + browserClickText.
            "fetchPageText" -> {
                val url = call.args["url"] as? String ?: return ToolExecutor.ToolResult(false, "missing url")
                try {
                    // v2.2.7: Wrap in withContext(IO) — was blocking main thread → ANR
                    val (respCode, rawHtml) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val request = okhttp3.Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
                            .build()
                        val response = client.newCall(request).execute()
                        response.code to (response.body?.string() ?: "")
                    }
                    // Strip HTML → clean text (CPU-bound, fast — OK on main thread)
                    val html = rawHtml
                    var text = html
                        .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
                        .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
                        .replace(Regex("(?is)<nav[^>]*>.*?</nav>"), " ")
                        .replace(Regex("(?is)<footer[^>]*>.*?</footer>"), " ")
                        .replace(Regex("(?is)<header[^>]*>.*?</header>"), " ")
                        .replace(Regex("(?is)<!--.*?-->"), " ")
                        .replace(Regex("(?i)<br\\s*/?>"), "\n")
                        .replace(Regex("(?i)</p>"), "\n\n")
                        .replace(Regex("(?i)</div>"), "\n")
                        .replace(Regex("(?i)</li>"), "\n")
                        .replace(Regex("(?i)</h[1-6]>"), "\n\n")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("&nbsp;"), " ")
                        .replace(Regex("&amp;"), "&")
                        .replace(Regex("&lt;"), "<")
                        .replace(Regex("&gt;"), ">")
                        .replace(Regex("&quot;"), "\"")
                        .replace(Regex("&#39;"), "'")
                        .replace(Regex("&[a-z]+;"), " ")
                        .replace(Regex("[ \\t]+"), " ")
                        .replace(Regex("\\n[ \\t]+"), "\n")
                        .replace(Regex("\\n{3,}"), "\n\n")
                        .trim()
                    if (text.length > 3000) text = text.take(3000) + "\n\n[...page truncated]"
                    val titleMatch = Regex("(?i)<title[^>]*>(.*?)</title>").find(html)
                    val title = titleMatch?.groupValues?.get(1)?.trim()?.take(200) ?: "(no title)"
                    ToolExecutor.ToolResult(true, "Page: $title\nURL: $url\nHTTP $respCode\n\nContent:\n$text")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Fetch error: ${e.message}")
                }
            }
            "downloadFile" -> {
                val url = call.args["url"] as? String ?: return ToolExecutor.ToolResult(false, "missing url")
                // v4.0.27: Fix broken default path — was using shell syntax $(System.currentTimeMillis())
                // which Kotlin treated as a literal string → saved to "$(S..." file.
                // Now uses proper Kotlin string template ${System.currentTimeMillis()}.
                // v4.0.27: Also resolve relative paths against the workspace downloads dir.
                val workspaceDir = "/storage/emulated/0/Documents/ai-workspace/downloads"
                val path = when {
                    call.args["path"] == null -> "$workspaceDir/${System.currentTimeMillis()}.bin"
                    (call.args["path"] as String).startsWith("/") -> call.args["path"] as String
                    else -> "$workspaceDir/${call.args["path"] as String}"
                }
                try {
                    val downloaded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        // v4.0.27: Follow redirects + add User-Agent (some sites reject no-UA)
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .build()
                        val request = okhttp3.Request.Builder()
                            .url(url)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                            .build()
                        val response = client.newCall(request).execute()
                        val bytes = response.body?.bytes() ?: return@withContext null
                        // v4.0.27: Check if we got an actual file vs an error page.
                        // If Content-Type is text/html and the download is tiny, it's likely an error.
                        val contentType = response.header("Content-Type", "") ?: ""
                        if (bytes.size < 500 && contentType.contains("text/html", ignoreCase = true)) {
                            return@withContext -1 to "Got HTML error page (${bytes.size} bytes, status ${response.code}). URL may be wrong or requires a browser."
                        }
                        val file = java.io.File(path)
                        file.parentFile?.mkdirs()
                        file.writeBytes(bytes)
                        bytes.size to contentType
                    }
                    if (downloaded != null) {
                        val (size, type) = downloaded
                        if (size == -1) {
                            ToolExecutor.ToolResult(false, type)  // type holds the error message
                        } else {
                            ToolExecutor.ToolResult(true, "Downloaded $size bytes to $path\nContent-Type: $type")
                        }
                    } else {
                        ToolExecutor.ToolResult(false, "Empty response — server returned no data")
                    }
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Download error: ${e.message}")
                }
            }
            "webSearch" -> {
                val query = call.args["query"] as? String ?: return ToolExecutor.ToolResult(false, "missing query")
                // v2.3.3: fetchTop — auto-fetch full text of top N results inline (1 call, saves N+1 calls)
                val fetchTop = (call.args["fetchTop"] as? Number)?.toInt() ?: 0
                val searchMethod = AIProvider.getSearchMethod(context)
                try {
                    val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                    val (searchUrl, parseMode) = when (searchMethod) {
                        "google" -> "https://www.google.com/search?q=$encodedQuery" to "google"
                        "bing" -> "https://www.bing.com/search?q=$encodedQuery&setlang=en" to "bing"
                        "google_app" -> {
                            // v2.2.3: Open Google Search app directly via Intent
                            val intent = android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH).apply {
                                putExtra("query", query)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            try {
                                context.startActivity(intent)
                                return ToolExecutor.ToolResult(true, "Opened Google Search app for: '$query'. Use readScreen() to see results after 3s.")
                            } catch (e: Exception) {
                                // Google Search app not available — fall back to DuckDuckGo
                                "https://html.duckduckgo.com/html/?q=$encodedQuery" to "duckduckgo"
                            }
                        }
                        else -> "https://html.duckduckgo.com/html/?q=$encodedQuery" to "duckduckgo"
                    }
                    // v2.2.6: Run HTTP request on IO dispatcher (was blocking main thread → NetworkOnMainThreadException)
                    val (body, responseCode) = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val request = okhttp3.Request.Builder()
                            .url(searchUrl)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
                            .apply {
                                if (parseMode == "proxy") {
                                    header("Content-Type", "application/json")
                                    post("""{"query":"$query","num":5}""".toRequestBody("application/json".toMediaType()))
                                }
                            }
                            .build()
                        val response = client.newCall(request).execute()
                        val respBody = response.body?.string() ?: ""
                        respBody to response.code
                    }

                    val results = StringBuilder()
                    val urlsToFetch = mutableListOf<String>()

                    if (parseMode == "proxy") {
                        // Proxy returns JSON
                        val parsed = org.json.JSONObject(body)
                        val json = parsed.optJSONArray("results") ?: org.json.JSONArray()
                        for (i in 0 until json.length()) {
                            val item = json.optJSONObject(i) ?: continue
                            val title = item.optString("name")
                            val snippet = item.optString("snippet")
                            val url = item.optString("url")
                            results.append("${i+1}. $title\n   $snippet\n   $url\n\n")
                            if (i < maxOf(1, fetchTop) && url.isNotEmpty()) urlsToFetch.add(url)
                        }
                    } else {
                        // HTML parsing — extract results based on search engine
                        val resultPattern = when (parseMode) {
                            "google" -> Regex("""<h3[^>]*>(.*?)</h3>.*?<a[^>]*href="/url\?q=([^&]+)""", RegexOption.DOT_MATCHES_ALL)
                            "bing" -> Regex("""<h2[^>]*><a[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                            else -> Regex("""<a[^>]*class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>.*?<a[^>]*class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
                        }
                        val matches = resultPattern.findAll(body).take(8)
                        var count = 0
                        for (match in matches) {
                            count++
                            var url = match.groupValues[1]
                            // DuckDuckGo wraps URLs in redirect
                            val uddgMatch = Regex("uddg=([^&]+)").find(url)
                            if (uddgMatch != null) {
                                url = java.net.URLDecoder.decode(uddgMatch.groupValues[1], "UTF-8")
                            }
                            val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                            val snippet = if (match.groupValues.size > 3) match.groupValues[3].replace(Regex("<[^>]+>"), "").trim() else ""
                            results.append("$count. $title\n   $snippet\n   $url\n\n")
                            if (count <= maxOf(1, fetchTop) && url.startsWith("http")) urlsToFetch.add(url)
                        }
                    }

                    if (results.isEmpty()) {
                        ToolExecutor.ToolResult(true, "No results found for: $query (via $searchMethod). Try a different search method in Settings, or use fetchPageText on a specific URL.")
                    } else {
                        // v2.3.3: If fetchTop > 0, fetch full text of top N URLs inline
                        val finalResult = if (fetchTop > 0 && urlsToFetch.isNotEmpty()) {
                            val fetchCount = minOf(fetchTop, urlsToFetch.size)
                            val fullTexts = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                // Fetch concurrently for speed — use coroutineScope to allow async
                                kotlinx.coroutines.coroutineScope {
                                    urlsToFetch.take(fetchCount).map { url ->
                                        async(kotlinx.coroutines.Dispatchers.IO) {
                                            try {
                                                val client = okhttp3.OkHttpClient.Builder()
                                                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                                                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                                                    .build()
                                                val req = okhttp3.Request.Builder()
                                                    .url(url)
                                                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36")
                                                    .build()
                                                val resp = client.newCall(req).execute()
                                                val html = resp.body?.string() ?: ""
                                                resp.code to html
                                            } catch (e: Exception) {
                                                -1 to ""
                                            }
                                        }.let { deferred -> deferred }
                                    }.awaitAll()
                                }
                            }
                            val sb = StringBuilder()
                            sb.append("=== SEARCH RESULTS ===\n")
                            sb.append(results.toString())
                            sb.append("\n=== FULL CONTENT (top $fetchCount) ===\n")
                            fullTexts.forEachIndexed { i, pair ->
                                val url = urlsToFetch[i]
                                val code = pair.first
                                val html = pair.second
                                sb.append("\n--- #${i+1}: $url (HTTP $code) ---\n")
                                if (html.isEmpty()) {
                                    sb.append("(fetch failed)\n")
                                } else {
                                    var text = html
                                        .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
                                        .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
                                        .replace(Regex("(?is)<nav[^>]*>.*?</nav>"), " ")
                                        .replace(Regex("(?is)<footer[^>]*>.*?</footer>"), " ")
                                        .replace(Regex("(?is)<header[^>]*>.*?</header>"), " ")
                                        .replace(Regex("(?is)<!--.*?-->"), " ")
                                        .replace(Regex("(?i)<br\\s*/?>"), "\n")
                                        .replace(Regex("(?i)</p>"), "\n\n")
                                        .replace(Regex("(?i)</div>"), "\n")
                                        .replace(Regex("(?i)</li>"), "\n")
                                        .replace(Regex("(?i)</h[1-6]>"), "\n\n")
                                        .replace(Regex("<[^>]+>"), " ")
                                        .replace(Regex("&nbsp;"), " ")
                                        .replace(Regex("&amp;"), "&")
                                        .replace(Regex("&lt;"), "<")
                                        .replace(Regex("&gt;"), ">")
                                        .replace(Regex("&quot;"), "\"")
                                        .replace(Regex("&#39;"), "'")
                                        .replace(Regex("&[a-z]+;"), " ")
                                        .replace(Regex("[ \\t]+"), " ")
                                        .replace(Regex("\\n[ \\t]+"), "\n")
                                        .replace(Regex("\\n{3,}"), "\n\n")
                                        .trim()
                                    if (text.length > 2500) text = text.take(2500) + "\n[...truncated]"
                                    val titleMatch = Regex("(?i)<title[^>]*>(.*?)</title>").find(html)
                                    val title = titleMatch?.groupValues?.get(1)?.trim()?.take(150) ?: "(no title)"
                                    sb.append("Title: $title\nContent:\n$text\n")
                                }
                            }
                            sb.toString()
                        } else {
                            results.toString()
                        }
                        ToolExecutor.ToolResult(true, finalResult)
                    }
                } catch (e: Exception) {
                    val errorMsg = e.message ?: e.toString()
                    ToolExecutor.ToolResult(false, "Search error ($searchMethod): $errorMsg")
                }
            }
            else -> ToolExecutor.ToolResult(false, "Unknown web tool: ${call.name}")
        }
    }
}
