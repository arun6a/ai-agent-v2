package com.ai.agent.util

/**
 * v4.0.99-collab3: RuleActionParser — extracted from RuleEngine (v3.0.0).
 *
 * RuleEngine supports two rule kinds:
 *   - SMART rules (natural language → LLM call)
 *   - DIRECT rules (offline tool-call syntax: `setBrightness(50)`, `speak("hello")`,
 *     multi-statement: `setBrightness(50); speak("good morning")`)
 *
 * This object carries the DIRECT-rule parsing logic so it can be unit-tested
 * on the JVM. RuleEngine.executeDirect() should delegate here (wiring GAP below).
 *
 * Algorithm (faithful copy of RuleEngine.executeDirect):
 *   1. stripPrefix    — remove "direct:" / "tool:" prefixes, trim.
 *   2. isDirectRule   — must match ^[a-zA-Z_][a-zA-Z0-9_]*\(.*\)$ after stripping.
 *   3. splitToolCalls — split on ";", trim, drop empties.
 *   4. parseToolCall  — regex ^(\w+)\((.*)\)$ then parse args:
 *        - args split on ","
 *        - part contains "="  → named arg  (key before first "=", value after)
 *        - otherwise          → positional arg, key = index ("0","1",...)
 *        - values: trim, strip surrounding double/single quotes,
 *          coerce to Int when possible, else keep String.
 *
 * ===== DOCUMENTED BUGS / GAPS (found while extracting — for the other AI) =====
 *
 * GAP-1 (HIGH — real bug): args split on "," is NOT quote-aware.
 *     `sendSMS("555123","hi, there")` → args {0:"555123", 1:"hi", 2:" there"}
 *     → message is silently split into two args. Any tool value containing a
 *     comma (messages, names, "Bengaluru, India") is corrupted.
 *     FIX: quote-aware splitter (track inQuote, split only on commas outside).
 *
 * GAP-2 (MEDIUM): quoted numeric values lose their type.
 *     `speak("50")` → cleanV="50" → toIntOrNull=50 → args{0: 50 (Int)}.
 *     The tool receives Int where the user quoted a String. FIX: only coerce
 *     unquoted values, or keep a raw-type flag.
 *
 * GAP-3 (LOW): `foo( )` → argsStr=" " (not empty) → single empty part →
 *     args{"0": ""} instead of an empty arg map.
 *
 * GAP-4 (LOW): named-arg KEYS are not unquoted: `foo("key"=v)` → key `"key"`
 *     (with literal quotes) → tool lookup by "key" fails.
 *
 * GAP-5 (LOW): isDirectRule uses regex `.` (no DOTALL) — multi-line actions
 *     (newline inside the parens) are classified as SMART rules silently.
 *
 * GAP-6 (INFO): chained prefixes strip only in one order:
 *     "direct:tool:foo(1)" → "foo(1)" (both removed) but
 *     "tool:direct:foo(1)" → "direct:foo(1)" (colon left → NOT direct).
 *     Harmless today, but worth knowing.
 *
 * GAP-7 (MEDIUM — real bug): removePrefix runs BEFORE trim, so leading
 *     whitespace defeats prefix removal: "  direct:foo(1)" → "direct:foo(1)"
 *     which then FAILS the direct-rule regex → the rule silently degrades to
 *     a SMART rule (extra LLM call, needs internet, slower). If rule.action
 *     is ever stored with leading whitespace, offline direct rules break.
 *     FIX: trim() first, then removePrefix.
 */
object RuleActionParser {

    data class ParsedToolCall(
        val toolName: String,
        val args: Map<String, Any>,
    )

    /** Strip rule prefixes. Same chained removePrefix as RuleEngine.cleanAction. */
    fun stripPrefix(action: String): String =
        action.removePrefix("direct:").removePrefix("tool:").trim()

    /** True when the action is a DIRECT (offline, no-LLM) tool call. */
    fun isDirectRule(action: String): Boolean =
        stripPrefix(action).matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*\\(.*\\)$"))

    /** Split a direct action into individual tool calls. */
    fun splitToolCalls(actionStr: String): List<String> =
        actionStr.split(";").map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Parse one tool call like `setBrightness(50)` or `sendSMS(to="555", msg="hi")`.
     * Returns null when the call doesn't match the tool-call syntax.
     */
    fun parseToolCall(toolCall: String): ParsedToolCall? {
        val match = Regex("^(\\w+)\\((.*)\\)$").find(toolCall.trim()) ?: return null
        val toolName = match.groupValues[1]
        val argsStr = match.groupValues[2]
        val args = mutableMapOf<String, Any>()

        // NOTE: phone numbers like "555123" become Int 555123 (GAP-2); only
        // values that overflow Int (e.g. "+919876543210") survive as String.
        if (argsStr.isNotEmpty()) {
            val parts = argsStr.split(",").map { it.trim() }
            parts.forEachIndexed { i, part ->
                if (part.contains("=")) {
                    val (k, v) = part.split("=", limit = 2)
                    val cleanV = v.trim().trim('"').trim('\'')
                    args[k.trim()] = cleanV.toIntOrNull() ?: cleanV
                } else {
                    val cleanV = part.trim().trim('"').trim('\'')
                    args[i.toString()] = cleanV.toIntOrNull() ?: cleanV
                }
            }
        }
        return ParsedToolCall(toolName, args)
    }
}
