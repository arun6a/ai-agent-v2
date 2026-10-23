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
    const val VERSION = "4.0.9"
}
