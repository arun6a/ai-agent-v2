package com.ai.agent.tools.categories

import android.content.Context
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * v4.0.92: Unified search tool — searches ALL retrieval sources in ONE call.
 *
 * Replaces the pattern of calling recallMemory + searchKnowledge + searchTools +
 * searchMcpServers separately (4 API calls, 4 iterations). Now the AI calls
 * searchAll(query) once and gets combined, ranked results from all sources.
 *
 * Sources searched in parallel:
 *   1. Memories (FTS5 BM25 via recallSimilar)
 *   2. Knowledge files (FTS5 via recallKnowledge)
 *   3. Built-in + custom tools (keyword via searchTools logic)
 *   4. MCP servers (registry API via McpRegistryClient)
 *
 * Results are returned grouped by source, top 3-5 per source, to keep the
 * response compact (avoids token bloat).
 */
object UnifiedSearch {

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        database: AgentDatabase,
    ): ToolExecutor.ToolResult {
        val query = call.args["query"] as? String
            ?: return ToolExecutor.ToolResult(false, "missing query. Example: searchAll(query=\"gmail\")")

        return coroutineScope {
            // Run all 4 searches in parallel for speed
            val memoriesDeferred = async {
                try {
                    database.recallSimilar(query, limit = 3)
                } catch (e: Exception) { emptyList() }
            }
            val knowledgeDeferred = async {
                try {
                    database.recallKnowledge(query, limit = 3)
                } catch (e: Exception) { emptyList() }
            }
            val mcpDeferred = async {
                try {
                    com.ai.agent.mcp.McpRegistryClient.searchAll(query)
                        .take(3)
                } catch (e: Exception) { emptyList() }
            }

            // Tools search is synchronous (in-memory, fast)
            val toolResults = searchToolsList(query, context)

            val memories = memoriesDeferred.await()
            val knowledge = knowledgeDeferred.await()
            val mcpResults = mcpDeferred.await()

            // Build combined output
            val sb = StringBuilder()
            sb.append("🔍 Unified search results for '$query':\n\n")

            // 1. Memories
            if (memories.isNotEmpty()) {
                sb.append("📋 MEMORIES (${memories.size}):\n")
                for ((key, value, score) in memories) {
                    sb.append("  • $key: ${value.take(150)}\n")
                }
                sb.append("\n")
            }

            // 2. Knowledge
            if (knowledge.isNotEmpty()) {
                sb.append("📚 KNOWLEDGE (${knowledge.size}):\n")
                for ((name, content, _) in knowledge) {
                    sb.append("  • $name: ${content.take(150)}...\n")
                }
                sb.append("\n")
            }

            // 3. Tools
            if (toolResults.isNotEmpty()) {
                sb.append("🛠 TOOLS (${toolResults.size}):\n")
                for ((name, desc) in toolResults) {
                    sb.append("  • $name: ${desc.take(100)}\n")
                }
                sb.append("\n")
            }

            // 4. MCP servers
            if (mcpResults.isNotEmpty()) {
                sb.append("🔌 MCP SERVERS (${mcpResults.size}):\n")
                for (result in mcpResults) {
                    val officialTag = if (result.isOfficial) " ⭐" else ""
                    sb.append("  • ${result.name}$officialTag: ${result.description.take(100)}\n")
                    if (result.url.isNotEmpty()) sb.append("    URL: ${result.url}\n")
                }
                sb.append("\n")
            }

            if (memories.isEmpty() && knowledge.isEmpty() && toolResults.isEmpty() && mcpResults.isEmpty()) {
                sb.append("No results found in any source. Try a different keyword.\n")
                sb.append("You can also create a custom tool (createCustomTool) or connector (createConnector).")
            } else {
                sb.append("Use the tool/source directly based on what you found.")
            }

            ToolExecutor.ToolResult(true, sb.toString().trim())
        }
    }

    /**
     * Search built-in + custom tools by keyword (reuses the same logic as searchTools).
     * Returns list of (name, description).
     */
    private fun searchToolsList(query: String, context: Context): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val queryLower = query.lowercase().trim()
        val queryWords = queryLower.split(Regex("\\s+")).filter { it.length > 1 }

        // Search built-in tool schemas
        for (schema in com.ai.agent.llm.ToolSchemas.allSchemas()) {
            val nameLower = schema.name.lowercase()
            val descLower = schema.description.lowercase()
            val matches = queryLower in nameLower || queryLower in descLower ||
                queryWords.any { word -> nameLower.contains(word) || descLower.contains(word) }
            if (matches) {
                results.add(Pair(schema.name, schema.description))
            }
        }

        // Search custom tools
        try {
            val customTools = com.ai.agent.tools.custom.CustomToolStore.load(context)
            for (tool in customTools) {
                val nameLower = tool.name.lowercase()
                val descLower = tool.description.lowercase()
                val matches = queryLower in nameLower || queryLower in descLower ||
                    queryWords.any { word -> nameLower.contains(word) || descLower.contains(word) }
                if (matches) {
                    results.add(Pair(tool.name, "(custom) ${tool.description}"))
                }
            }
        } catch (_: Exception) {}

        // Search connector endpoint tools
        try {
            val connectors = com.ai.agent.tools.custom.ConnectorStore.load(context)
            for (conn in connectors) {
                for (ep in conn.endpoints) {
                    val toolName = "${conn.name}__${ep.name}"
                    val nameLower = toolName.lowercase()
                    val descLower = ep.description.lowercase()
                    val matches = queryLower in nameLower || queryLower in descLower ||
                        queryWords.any { word -> nameLower.contains(word) || descLower.contains(word) }
                    if (matches) {
                        results.add(Pair(toolName, "(connector:${conn.name}) ${ep.description}"))
                    }
                }
            }
        } catch (_: Exception) {}

        return results.take(5)  // max 5 tool results
    }
}
