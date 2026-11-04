package com.ai.agent.mcp

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * v4.0.79: MCP Registry Client — searches the official MCP registry + web.
 *
 * Two-layer search:
 * 1. Official Registry API (structured JSON with URLs, transport, auth)
 *    GET https://registry.modelcontextprotocol.io/v0/servers?search=<query>
 *
 * 2. Web Search via DuckDuckGo HTML (same approach as Aira's existing webSearch tool)
 *    Covers community registries: mcpservers.org, mcp.so, pulsemcp.com, glama.ai
 *
 * Results are merged + deduplicated. Official results get priority.
 */
object McpRegistryClient {
    private const val TAG = "McpRegistry"
    private const val REGISTRY_BASE = "https://registry.modelcontextprotocol.io/v0/servers"

    private val OFFICIAL_PUBLISHERS = setOf(
        "com.googleapis", "com.notion", "com.stripe", "com.atlassian",
        "com.cloudflare.mcp", "com.microsoft", "com.github", "com.slack",
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Search the official MCP registry API.
     * Returns structured results with URLs, transport, auth info.
     */
    suspend fun searchOfficialRegistry(query: String, limit: Int = 20): List<McpSearchResult> {
        return withContext(Dispatchers.IO) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = "$REGISTRY_BASE?search=$encodedQuery&limit=$limit"
                Log.i(TAG, "Searching official registry: $url")

                val request = Request.Builder().url(url)
                    .header("Accept", "application/json")
                    .build()
                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.w(TAG, "Registry returned ${response.code}")
                    return@withContext emptyList()
                }

                val json = JSONObject(body)
                val servers = json.optJSONArray("servers") ?: return@withContext emptyList()
                val results = mutableListOf<McpSearchResult>()
                val seenNames = mutableSetOf<String>()

                for (i in 0 until servers.length()) {
                    val entry = servers.optJSONObject(i) ?: continue
                    val srv = entry.optJSONObject("server") ?: continue
                    val meta = entry.optJSONObject("_meta") ?: JSONObject()
                    val officialMeta = meta.optJSONObject("io.modelcontextprotocol.registry/official") ?: JSONObject()

                    val name = srv.optString("name", "")
                    if (name.isEmpty()) continue

                    val isLatest = officialMeta.optBoolean("isLatest", false)
                    if (name in seenNames && !isLatest) continue
                    seenNames.add(name)

                    val publisher = if (name.contains("/")) name.substringBefore("/") else ""

                    val remotes = srv.optJSONArray("remotes")
                    var transport = ""
                    var remoteUrl = ""
                    if (remotes != null && remotes.length() > 0) {
                        val remote = remotes.optJSONObject(0)
                        transport = remote?.optString("type", "") ?: ""
                        remoteUrl = remote?.optString("url", "") ?: ""
                    }

                    val repo = srv.optJSONObject("repository")
                    val repoUrl = repo?.optString("url", "") ?: ""

                    results.add(McpSearchResult(
                        name = name,
                        description = srv.optString("description", ""),
                        publisher = publisher,
                        isOfficial = isOfficialPublisher(publisher),
                        transport = transport,
                        url = remoteUrl,
                        version = srv.optString("version", ""),
                        isLatest = isLatest,
                        canAutoConnect = remoteUrl.isNotEmpty() && transport.isNotEmpty(),
                        source = "official_registry",
                        repositoryUrl = repoUrl,
                    ))
                }

                Log.i(TAG, "Official: ${results.size} servers for '$query'")
                results
            } catch (e: Exception) {
                Log.e(TAG, "Official search failed: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * Search community registries via DuckDuckGo HTML scraping.
     * Covers: mcpservers.org, mcp.so, pulsemcp.com, glama.ai, mcpmarket.com
     *
     * Same approach as Aira's existing webSearch tool.
     */
    suspend fun searchCommunityRegistries(query: String): List<McpSearchResult> {
        return withContext(Dispatchers.IO) {
            try {
                val searchQuery = "$query MCP server"
                val encodedQuery = URLEncoder.encode(searchQuery, "UTF-8")
                val url = "https://html.duckduckgo.com/html/?q=$encodedQuery"
                Log.i(TAG, "Searching community via DuckDuckGo: $url")

                val request = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .build()
                val response = httpClient.newCall(request).execute()
                val html = response.body?.string() ?: ""

                // Parse DuckDuckGo HTML results
                val results = mutableListOf<McpSearchResult>()
                // Simple regex to extract result links + titles
                val resultPattern = Regex(
                    """<a[^>]*class="result__a"[^>]*href="([^"]*)"[^>]*>(.*?)</a>""",
                    RegexOption.DOT_MATCHES_ALL
                )
                for (match in resultPattern.findAll(html).take(10)) {
                    val resultUrl = match.groupValues[1]
                    val title = match.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                    if (title.isEmpty() || resultUrl.isEmpty()) continue

                    // Determine which community registry
                    val source = when {
                        resultUrl.contains("mcpservers.org") -> "mcpservers.org"
                        resultUrl.contains("mcp.so") -> "mcp.so"
                        resultUrl.contains("pulsemcp.com") -> "pulsemcp.com"
                        resultUrl.contains("glama.ai") -> "glama.ai"
                        resultUrl.contains("mcpmarket.com") -> "mcpmarket.com"
                        else -> "web"
                    }

                    results.add(McpSearchResult(
                        name = title,
                        description = "$query MCP server (from $source)",
                        publisher = source,
                        isOfficial = false,
                        transport = "",
                        url = resultUrl,
                        version = "",
                        isLatest = true,
                        canAutoConnect = false,
                        source = "community ($source)",
                        repositoryUrl = resultUrl,
                    ))
                }

                Log.i(TAG, "Community: ${results.size} results for '$query'")
                results
            } catch (e: Exception) {
                Log.e(TAG, "Community search failed: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * Full multi-registry search. Runs both layers in parallel, merges + deduplicates.
     */
    suspend fun searchAll(query: String): List<McpSearchResult> {
        return coroutineScope {
            val officialDeferred = async { searchOfficialRegistry(query) }
            val communityDeferred = async { searchCommunityRegistries(query) }

            val official = officialDeferred.await()
            val community = communityDeferred.await()

            val all = mutableListOf<McpSearchResult>()
            // Official + auto-connectable first, then community
            all.addAll(official.sortedWith(
                compareByDescending<McpSearchResult> { it.isOfficial }
                    .thenByDescending { it.canAutoConnect }
            ))
            all.addAll(community)

            // Deduplicate by name
            val seen = mutableSetOf<String>()
            all.filter { result ->
                val key = result.name.lowercase()
                if (key in seen) false else { seen.add(key); true }
            }
        }
    }

    private fun isOfficialPublisher(publisher: String): Boolean {
        if (publisher.isEmpty()) return false
        if (publisher in OFFICIAL_PUBLISHERS) return true
        for (official in OFFICIAL_PUBLISHERS) {
            if (publisher.startsWith("$official.") || publisher == official) return true
        }
        return false
    }
}
