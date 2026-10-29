package com.ai.agent.tools.categories

import android.content.Context
import android.util.Log
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor

/**
 * Skill tools category — extracted from ToolExecutor in v3.3.13.
 *
 * Tools: executeSkill, listSkills, createSkill, deleteSkill, getSkill.
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object SkillTools {

    private const val TAG = "SkillTools"

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            // ==================== SKILL MANAGEMENT (v6.1.0) ====================
            "createSkill" -> {
                val name = call.args["name"] as? String ?: return ToolExecutor.ToolResult(false, "missing name")
                val description = call.args["description"] as? String ?: return ToolExecutor.ToolResult(false, "missing description")
                val trigger = call.args["trigger"] as? String ?: return ToolExecutor.ToolResult(false, "missing trigger")
                val instructions = call.args["instructions"] as? String ?: return ToolExecutor.ToolResult(false, "missing instructions")
                try {
                    val skillsDir = java.io.File("/storage/emulated/0/Aira/skills")
                    if (!skillsDir.exists()) skillsDir.mkdirs()
                    val skillFile = java.io.File(skillsDir, "${name.replace(" ", "_")}.md")
                    val content = """---
name: ${name.replace(" ", "-")}
description: $description
trigger: $trigger
version: 1.0
---
# ${name.replaceFirstChar { it.uppercase() }}

$instructions
"""
                    skillFile.writeText(content)
                    ToolExecutor.ToolResult(true, "Skill '$name' created successfully at ${skillFile.name}. It will be available next time skills are loaded.")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Failed to create skill: ${e.message}")
                }
            }
            "deleteSkill" -> {
                val name = call.args["name"] as? String ?: return ToolExecutor.ToolResult(false, "missing name")
                try {
                    val skillsDir = java.io.File("/storage/emulated/0/Aira/skills")
                    val assetSkillsDir = java.io.File("assets/skills")  // read-only, can't delete
                    val userSkillFile = java.io.File(skillsDir, "${name.replace(" ", "_")}.md")
                    if (userSkillFile.exists()) {
                        userSkillFile.delete()
                        ToolExecutor.ToolResult(true, "Skill '$name' deleted.")
                    } else {
                        ToolExecutor.ToolResult(false, "Skill '$name' not found in user skills. Built-in skills (in assets/) cannot be deleted.")
                    }
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Failed to delete skill: ${e.message}")
                }
            }
            "getSkill" -> {
                val name = call.args["name"] as? String ?: return ToolExecutor.ToolResult(false, "missing name")
                try {
                    val skillManager = com.ai.agent.skills.SkillManager(context).also { it.loadSkills() }
                    val skill = skillManager.findSkill(name)
                    if (skill != null) {
                        ToolExecutor.ToolResult(true, "Skill: ${skill.name}\nDescription: ${skill.description}\nTriggers: ${skill.triggers}\n\nInstructions:\n${skill.body}")
                    } else {
                        ToolExecutor.ToolResult(false, "Skill '$name' not found. Use listSkills() to see available skills.")
                    }
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Failed to get skill: ${e.message}")
                }
            }
            // ==================== SKILLS ====================
            "executeSkill" -> {
                val skillName = call.args["name"] as? String ?: return ToolExecutor.ToolResult(false, "missing skill name")
                try {
                    val skillManager = com.ai.agent.skills.SkillManager(context)
                    skillManager.loadSkills()
                    val body = skillManager.getSkillBody(skillName)
                    if (body != null) {
                        ToolExecutor.ToolResult(true, "SKILL INSTRUCTIONS LOADED — follow these instructions:\n\n$body\n\nEND OF SKILL INSTRUCTIONS. Now execute the steps above using the available tools.")
                    } else {
                        ToolExecutor.ToolResult(false, "Skill not found: $skillName. Available skills: ${skillManager.getSkills().map { it.name }}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "executeSkill error", e)
                    ToolExecutor.ToolResult(false, "Skill error: ${e.message}")
                }
            }
            "listSkills" -> {
                try {
                    val skillManager = com.ai.agent.skills.SkillManager(context)
                    skillManager.loadSkills()
                    val skills = skillManager.getSkills()
                    if (skills.isEmpty()) {
                        ToolExecutor.ToolResult(true, "No skills installed.")
                    } else {
                        val sb = StringBuilder("Available skills (${skills.size}):\n")
                        for (s in skills) {
                            sb.append("- ${s.name}: ${s.description}\n  Trigger: ${s.trigger}\n")
                        }
                        ToolExecutor.ToolResult(true, sb.toString())
                    }
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Error listing skills: ${e.message}")
                }
            }
            else -> ToolExecutor.ToolResult(false, "Unknown skill tool: ${call.name}")
        }
    }
}
