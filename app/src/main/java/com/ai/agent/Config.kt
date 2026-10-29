package com.ai.agent

/**
 * App configuration.
 * The LLM proxy runs on the sandbox and forwards requests to Z.ai API.
 * The phone calls the public sandbox URL, not the internal API directly.
 *
 * NOTE: For the actual proxy URL used at runtime, see AIProvider.kt —
 * it is configurable in Settings and stored in SharedPreferences.
 * This Config object holds only static constants.
 */
object Config {
    // App version (must match app/build.gradle.kts versionName)
    const val VERSION = "4.0.37"

    // v4.0.38: Aira's working directory on external storage.
    // All files (skills, downloads, attachments, knowledge, screenshots) live here.
    // Changed from /storage/emulated/0/Aira/ → /storage/emulated/0/Aira/
    // for a cleaner, shorter path that's easier for users to find.
    const val WORKSPACE_DIR = "/storage/emulated/0/Aira"
    const val ATTACHMENTS_DIR = "$WORKSPACE_DIR/attachments"
    const val SKILLS_DIR = "$WORKSPACE_DIR/skills"
    const val KNOWLEDGE_DIR = "$WORKSPACE_DIR/knowledge"
    const val DOWNLOADS_DIR = "$WORKSPACE_DIR/downloads"
    const val SCREENSHOTS_DIR = "$WORKSPACE_DIR/screenshots"
}
