package com.ai.agent.util

import com.ai.agent.llm.LLMClient

/**
 * v4.0.99-collab4: ToolCallNormalizer — extracted from ToolExecutor
 * (normalizePositionalArgs, v4.0.20 + v4.0.96) so the arg-fixing layer can be
 * unit-tested on the JVM. ToolExecutor.executeTool() should delegate here
 * (wiring GAP below).
 *
 * What it does (in order):
 *  1. GLOBAL aliases — "app"/"pkg" → "package" for EVERY tool; additionally
 *     "name" → "package" only for the 8 tools that actually take a package
 *     param (the v4.0.20 fix: a global "name"→"package" broke createRule,
 *     createSkill, saveToKnowledge, searchContacts, ... which legitimately
 *     use `name`).
 *  2. TOOL-SPECIFIC aliases (v4.0.96, from real log analysis of 4717 lines /
 *     22 failures) — e.g. recallMemory(key=) → query=, createRule(ruleName=)
 *     → name=, createConnector(baseUrl=) → apiBase=.
 *     Rule: an alias is applied ONLY when the canonical key is not already
 *     present (canonical value always wins).
 *  3. POSITIONAL → NAMED mapping — text-parsed calls like tap(970, 180)
 *     arrive as {"0":970,"1":180}; this maps them to the tool's positional
 *     signature ("x","y") etc. Named args are copied first; a positional
 *     value never overwrites an existing named arg.
 *
 * ===== DOCUMENTED BUGS / GAPS (found while extracting — for the other AI) =====
 *
 * GAP-1 (MEDIUM): alias keys are NOT removed when the canonical key already
 *     exists. `launchApp(package="com.x", app="com.y")` keeps BOTH "package"
 *     and "app" keys — tools that iterate args will see a stray "app" key.
 *     Same for tool-specific aliases (recallMemory(query=, key=) keeps "key").
 *
 * GAP-2 (MEDIUM): global "app"/"pkg" aliases apply to ALL tools, even ones
 *     with no package concept. `setVolume(pkg="5")` grows a "package":"5"
 *     arg. Harmless today (tools ignore unknown keys) but pollutes logs and
 *     could collide with a future tool that has a real "package" param.
 *
 * GAP-3 (LOW): a numeric-but-non-canonical key ("01", " 1", "-1", "+1")
 *     counts as positional (toIntOrNull() != null), triggers the mapping
 *     path, but never matches any index — the value is SILENTLY DROPPED.
 *
 * GAP-4 (LOW): positional values beyond the argOrder length are dropped
 *     without any log: `tap(0=1, 1=2, 2=3)` → "2" vanishes.
 *
 * GAP-5 (INFO): tools not in the positional map keep raw {"0":...} keys
 *     (e.g. getCurrentLocation(0="gps")). The tool then sees "0" instead of
 *     its named param. The map covers ~35 tools; new tools must remember to
 *     extend it.
 *
 * GAP-6 (INFO): matching is EXACT tool name — the caller must resolve the
 *     canonical name first (ToolExecutor does this via case-insensitive
 *     lookup before calling; see ToolNameResolver).
 */
object ToolCallNormalizer {

    /** Tools whose `package` param may legitimately arrive as "name". */
    private val toolsWithPackageArg = setOf(
        "launchApp", "searchInApp", "forceStopApp", "uninstallApp",
        "getAppInfo", "openActivity", "openAppWithData", "searchInChrome"
    )

    /** Positional signatures per tool (index → param name). */
    private val positionalOrder: Map<String, List<String>> = mapOf(
        "tap" to listOf("x", "y"),
        "swipe" to listOf("x1", "y1", "x2", "y2"),
        "type" to listOf("text"),
        "clickByText" to listOf("text"),
        "speak" to listOf("text"),
        "launchApp" to listOf("package"),
        "searchInApp" to listOf("package", "query"),
        "webSearch" to listOf("query", "fetchTop"),
        "openGoogleSearch" to listOf("query", "fetchTop"),
        "browserOpen" to listOf("url"),
        "openUrl" to listOf("url"),
        "browserClickText" to listOf("selector"),
        "browserClickElement" to listOf("selector"),
        "browserGetText" to listOf("selector"),
        "browserFillForm" to listOf("field", "value"),
        "remember" to listOf("key", "value"),
        "recall" to listOf("query"),
        "recallSimilar" to listOf("query"),
        "searchMemory" to listOf("query"),
        "setVolume" to listOf("level"),
        "setBrightness" to listOf("level"),
        "sendSMS" to listOf("number", "message"),
        "callContact" to listOf("number"),
        "setAlarm" to listOf("hour", "minute"),
        "setTimer" to listOf("seconds"),
        "takeScreenshotToGallery" to emptyList(),
        "scrollDown" to emptyList(),
        "scrollUp" to emptyList(),
        "pressBack" to emptyList(),
        "pressHome" to emptyList(),
        "pressEnter" to emptyList(),
        "submitInput" to emptyList(),
        "recallAll" to emptyList(),
        "listInstalledApps" to emptyList(),
    )

    /** Tool-specific alias maps (v4.0.96, from real log analysis). */
    private val toolSpecificAliases: Map<String, Map<String, String>> = mapOf(
        "recallMemory" to mapOf("key" to "query", "q" to "query", "search" to "query"),
        "createCalendarEvent" to mapOf(
            "start" to "startTime", "duration" to "durationMinutes", "dur" to "durationMinutes"
        ),
        "recallKnowledge" to mapOf("key" to "query", "q" to "query", "search" to "query"),
        "createRule" to mapOf("ruleName" to "name", "rule_name" to "name"),
        "createConnector" to mapOf(
            "desc" to "description", "baseUrl" to "apiBase", "base" to "apiBase", "url" to "apiBase"
        ),
    )

    /**
     * Normalize a tool call: alias remapping + positional→named conversion.
     * Always returns a ToolCall (possibly with unchanged args).
     */
    fun normalize(call: LLMClient.ToolCall): LLMClient.ToolCall {
        // 1. Global aliases (app/pkg always; name→package only for package tools)
        val argAliases = if (call.name in toolsWithPackageArg) {
            mapOf(
                "app" to "package",
                "pkg" to "package",
                "name" to "package",
            )
        } else {
            mapOf(
                "app" to "package",
                "pkg" to "package",
            )
        }
        val remappedArgs = call.args.toMutableMap()
        // v4.0.100: GAP-1 fix — ALWAYS remove alias keys, even if canonical exists.
        for ((alias, canonical) in argAliases) {
            if (alias in remappedArgs) {
                if (canonical !in remappedArgs) {
                    remappedArgs[canonical] = remappedArgs.remove(alias)!!
                } else {
                    remappedArgs.remove(alias)  // v4.0.100: discard alias, keep canonical
                }
            }
        }

        // 2. Tool-specific aliases — same GAP-1 fix
        toolSpecificAliases[call.name]?.forEach { (alias, canonical) ->
            if (alias in remappedArgs) {
                if (canonical !in remappedArgs) {
                    remappedArgs[canonical] = remappedArgs.remove(alias)!!
                } else {
                    remappedArgs.remove(alias)  // v4.0.100: discard alias, keep canonical
                }
            }
        }

        val rebuilt = LLMClient.ToolCall(call.name, remappedArgs)

        // 3. Positional → named
        val hasPositional = rebuilt.args.keys.any { it.toIntOrNull() != null }
        if (!hasPositional) return rebuilt

        val argOrder = positionalOrder[rebuilt.name] ?: emptyList()
        if (argOrder.isEmpty()) return rebuilt

        val newArgs = mutableMapOf<String, Any>()
        for ((key, value) in rebuilt.args) {
            if (key.toIntOrNull() == null) {
                newArgs[key] = value
            }
        }
        for ((pos, name) in argOrder.withIndex()) {
            val posValue = rebuilt.args[pos.toString()]
            if (posValue != null && !newArgs.containsKey(name)) {
                newArgs[name] = posValue
            }
        }
        return LLMClient.ToolCall(rebuilt.name, newArgs)
    }
}
