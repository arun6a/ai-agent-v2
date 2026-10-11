package com.ai.agent.skills

import android.content.Context
import android.util.Log
import java.io.InputStreamReader

/**
 * Manages skill files (.md with YAML frontmatter) for the AI agent.
 *
 * Skills are Markdown instructions that the LLM reads and follows.
 * Each skill file has YAML frontmatter (metadata) + Markdown body (instructions).
 *
 * Based on OpenClaw's SKILL.md format — adapted for Android.
 *
 * File structure:
 * assets/skills/
 *   ├── morning_briefing.md
 *   ├── check_price.md
 *   └── send_whatsapp.md
 *
 * Each file:
 * ---
 * name: morning-briefing
 * description: Give a morning briefing
 * trigger: morning briefing|good morning|what's my morning
 * tools_used: [getCalendarEvents, webSearch, getBatteryLevel]
 * version: 1.0
 * ---
 * # Morning Briefing
 * When the user asks for a morning briefing:
 * 1. Call getCalendarEvents() ...
 */
class SkillManager(private val context: Context) {

    companion object {
        private const val TAG = "SkillManager"
        private const val SKILLS_DIR = "skills"
    }

    data class Skill(
        val name: String,
        val description: String,
        val trigger: String,
        val triggers: List<String>,
        val toolsUsed: List<String>,
        val version: String,
        val body: String,
        val fileName: String
    )

    private var skills: List<Skill> = emptyList()

    fun loadSkills(): List<Skill> {
        val loaded = mutableListOf<Skill>()
        // v6.1.0: Load from assets (built-in) + user storage (created via chat)
        try {
            val assetFiles = context.assets.list(SKILLS_DIR) ?: emptyArray()
            Log.i(TAG, "Found ${assetFiles.size} built-in skill files: ${assetFiles.toList()}")

            for (fileName in assetFiles) {
                if (!fileName.endsWith(".md")) continue
                try {
                    val content = InputStreamReader(context.assets.open("$SKILLS_DIR/$fileName")).readText()
                    val skill = parseSkill(content, fileName)
                    if (skill != null) {
                        loaded.add(skill)
                        Log.i(TAG, "Loaded built-in skill: ${skill.name} (triggers: ${skill.triggers})")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load skill: $fileName", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No skills directory found in assets", e)
        }

        // v6.1.0: Load user-created skills from storage
        try {
            val userSkillsDir = java.io.File("/storage/emulated/0/Documents/ai-workspace/skills")
            if (userSkillsDir.exists()) {
                val userFiles = userSkillsDir.listFiles { f -> f.extension == "md" } ?: emptyArray()
                Log.i(TAG, "Found ${userFiles.size} user skill files")
                for (file in userFiles) {
                    try {
                        val content = file.readText()
                        val skill = parseSkill(content, file.name)
                        if (skill != null) {
                            loaded.add(skill)
                            Log.i(TAG, "Loaded user skill: ${skill.name} (triggers: ${skill.triggers})")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load user skill: ${file.name}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load user skills", e)
        }

        skills = loaded
        return loaded
    }

    /** v6.1.0: Find a skill by name (for getSkill tool) */
    fun findSkill(name: String): Skill? {
        return skills.find { it.name.equals(name, ignoreCase = true) || it.name.replace("-", " ").equals(name, ignoreCase = true) }
    }

    fun getSkills(): List<Skill> = skills

    fun getSkill(name: String): Skill? {
        return skills.find { it.name.equals(name, ignoreCase = true) }
    }

    fun matchTrigger(userMessage: String): Skill? {
        val lower = userMessage.lowercase().trim()
        for (skill in skills) {
            for (trigger in skill.triggers) {
                if (lower.contains(trigger.lowercase())) {
                    Log.i(TAG, "Message matched skill: ${skill.name} (trigger: $trigger)")
                    return skill
                }
            }
        }
        return null
    }

    /** v6.0.0: Get count of loaded skills — for AgentFragment UI */
    fun getSkillCount(): Int = skills.size

    fun generatePromptSection(): String {
        if (skills.isEmpty()) return ""
        val sb = StringBuilder("\n## Available Skills\n")
        sb.append("Use executeSkill(name) to activate a skill. The skill's instructions will be loaded automatically.\n\n")
        for (skill in skills) {
            sb.append("- ${skill.name}: ${skill.description}\n")
            sb.append("  Trigger: ${skill.trigger}\n")
        }
        return sb.toString()
    }

    fun getSkillBody(name: String): String? {
        val skill = getSkill(name) ?: return null
        return skill.body
    }

    private fun parseSkill(content: String, fileName: String): Skill? {
        val frontmatterEnd = findFrontmatterEnd(content)
        if (frontmatterEnd < 0) {
            Log.w(TAG, "No YAML frontmatter found in $fileName")
            return null
        }
        val frontmatter = content.substring(3, frontmatterEnd)
        val body = content.substring(frontmatterEnd + 3).trim()

        val name = parseYamlValue(frontmatter, "name") ?: fileName.removeSuffix(".md")
        val description = parseYamlValue(frontmatter, "description") ?: ""
        val trigger = parseYamlValue(frontmatter, "trigger") ?: ""
        val version = parseYamlValue(frontmatter, "version") ?: "1.0"
        val toolsUsedStr = parseYamlValue(frontmatter, "tools_used") ?: ""

        val triggers = if (trigger.isNotEmpty()) {
            trigger.split("|").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        } else emptyList()

        val toolsUsed = if (toolsUsedStr.isNotEmpty()) {
            toolsUsedStr.trim('[', ']', ' ').split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else emptyList()

        return Skill(name, description, trigger, triggers, toolsUsed, version, body, fileName)
    }

    private fun findFrontmatterEnd(content: String): Int {
        if (!content.startsWith("---")) return -1
        val secondDash = content.indexOf("---", 3)
        return if (secondDash > 0) secondDash else -1
    }

    private fun parseYamlValue(yaml: String, key: String): String? {
        val regex = Regex("(?im)^$key\\s*:\\s*(.+)$")
        val match = regex.find(yaml) ?: return null
        return match.groupValues[1].trim().trim('"', '\'')
    }
}
