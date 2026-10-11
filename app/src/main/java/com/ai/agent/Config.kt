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
    // Default screen resolution (Nothing A015)
    const val SCREEN_WIDTH = 1080
    const val SCREEN_HEIGHT = 2400

    // App version (must match app/build.gradle.kts versionName)
    const val VERSION = "6.4.2"
}
