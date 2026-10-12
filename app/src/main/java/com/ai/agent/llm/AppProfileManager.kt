package com.ai.agent.llm

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * AppProfileManager (v2.3.3) — replaces the old PluginManager.
 *
 * Instead of duplicating tool lists in JSON files, this manager:
 * 1. Defines built-in "app profiles" for popular apps (WhatsApp, Spotify, YouTube, etc.)
 * 2. Auto-detects which are installed on this device via PackageManager
 * 3. Lets the user enable/disable AI access per-app (privacy control)
 * 4. Generates a small prompt section (~150-300 tokens) listing only installed+enabled apps
 *    with their shortcut tool, deep link, and tip
 *
 * Token cost comparison:
 * - Old PluginManager: ~3000 tokens (always, all 11 plugin JSON files)
 * - New AppProfileManager: ~150-300 tokens (only enabled + installed apps)
 */
object AppProfileManager {
    private const val TAG = "AppProfileManager"
    private const val PREFS = "ai_agent_app_profiles"
    private const val KEY_ENABLED_PREFIX = "app_enabled_"
    private const val CUSTOM_DIR = "app_profiles"

    enum class AppCategory {
        MESSAGING, SOCIAL, MEDIA, SHOPPING, UTILITY, PRODUCTIVITY
    }

    data class AppProfile(
        val packageName: String,
        val displayName: String,
        val category: AppCategory,
        val shortcutTool: String? = null,
        val shortcutDesc: String? = null,
        val deepLink: String? = null,
        val tip: String? = null,
        val installed: Boolean = false,
        var enabled: Boolean = false,
    )

    /**
     * Built-in profiles for popular apps.
     * Each profile tells the AI:
     * - Whether the app is installed
     * - Which shortcut tool to use (if any) — 1-call interaction
     * - The deep link prefix (if any) — for openDeepLink
     * - A tip on how to interact with the app's UI
     *
     * Adding a new app = add one entry here. No JSON file, no separate prompt update.
     */
    private val BUILTIN_PROFILES = listOf(
        // === MESSAGING ===
        AppProfile(
            packageName = "com.whatsapp",
            displayName = "WhatsApp",
            category = AppCategory.MESSAGING,
            shortcutTool = "openWhatsAppChat(phone, msg?)",
            shortcutDesc = "Open WhatsApp chat with phone + pre-filled msg. 1 call.",
            deepLink = "https://wa.me/{phone}",
            tip = "Search at tap(970,200). NEVER clickByText('Search'). Use openWhatsAppChat for 1-call send.",
        ),
        AppProfile(
            packageName = "org.telegram.messenger",
            displayName = "Telegram",
            category = AppCategory.MESSAGING,
            shortcutTool = "openTelegramChat(username)",
            shortcutDesc = "Open Telegram chat with username. 1 call.",
            deepLink = "https://t.me/{username}",
            tip = "Use openTelegramChat(username) — 1 call.",
        ),
        AppProfile(
            packageName = "com.facebook.orca",
            displayName = "Messenger",
            category = AppCategory.MESSAGING,
            deepLink = "https://m.me/{username}",
            tip = "launchApp then tap search bar (top).",
        ),
        AppProfile(
            packageName = "com.google.android.gm",
            displayName = "Gmail",
            category = AppCategory.MESSAGING,
            shortcutTool = "composeEmail(to, subject, body)",
            shortcutDesc = "Open Gmail composer with prefilled fields. 1 call.",
            tip = "Use composeEmail — 1 call. Avoid launchApp + manual compose.",
        ),

        // === SOCIAL ===
        AppProfile(
            packageName = "com.instagram.android",
            displayName = "Instagram",
            category = AppCategory.SOCIAL,
            shortcutTool = "openInstagramProfile(username)",
            shortcutDesc = "Open Instagram profile by username. 1 call.",
            deepLink = "https://instagram.com/{username}",
            tip = "Use openInstagramProfile(username) — 1 call.",
        ),
        AppProfile(
            packageName = "com.google.android.youtube",
            displayName = "YouTube",
            category = AppCategory.SOCIAL,
            shortcutTool = "searchInApp(\"com.google.android.youtube\", query)",
            shortcutDesc = "Open YouTube + search query. 1 call.",
            tip = "Search at tap(970,180). NEVER clickByText('Search'). Use searchInApp then tap first video.",
        ),
        AppProfile(
            packageName = "com.twitter.android",
            displayName = "X (Twitter)",
            category = AppCategory.SOCIAL,
            deepLink = "https://x.com/{username}",
            tip = "Use openDeepLink with x.com URL or launchApp + tap search.",
        ),
        AppProfile(
            packageName = "com.facebook.katana",
            displayName = "Facebook",
            category = AppCategory.SOCIAL,
            deepLink = "https://facebook.com/{username}",
            tip = "launchApp + tap search bar (top).",
        ),

        // === MEDIA ===
        AppProfile(
            packageName = "com.spotify.music",
            displayName = "Spotify",
            category = AppCategory.MEDIA,
            shortcutTool = "playSpotify(query)",
            shortcutDesc = "Open Spotify + search + play. 1 call.",
            tip = "Use playSpotify(query) — 1 call. Don't manual-tap.",
        ),
        AppProfile(
            packageName = "com.google.android.apps.youtube.music",
            displayName = "YouTube Music",
            category = AppCategory.MEDIA,
            shortcutTool = "searchInApp(\"com.google.android.apps.youtube.music\", query)",
            shortcutDesc = "Open YouTube Music + search. 1 call.",
            tip = "Search at tap(970,180).",
        ),
        AppProfile(
            packageName = "com.netflix.mediaclient",
            displayName = "Netflix",
            category = AppCategory.MEDIA,
            tip = "launchApp then tap search icon (top-right).",
        ),

        // === SHOPPING ===
        AppProfile(
            packageName = "in.amazon.mShop.android.shopping",
            displayName = "Amazon",
            category = AppCategory.SHOPPING,
            deepLink = "amzn://",
            tip = "Use browserOpen('amazon.in/s?k=...') or launchApp + tap search bar.",
        ),
        AppProfile(
            packageName = "com.flipkart.android",
            displayName = "Flipkart",
            category = AppCategory.SHOPPING,
            tip = "launchApp then tap search bar (top, ~540,120).",
        ),

        // === UTILITY ===
        AppProfile(
            packageName = "com.google.android.apps.maps",
            displayName = "Google Maps",
            category = AppCategory.UTILITY,
            shortcutTool = "openMapsLocation(query)",
            shortcutDesc = "Open Google Maps at a location. 1 call.",
            deepLink = "geo:0,0?q={query}",
            tip = "Use openMapsLocation(query) — 1 call.",
        ),
        AppProfile(
            packageName = "com.android.chrome",
            displayName = "Chrome",
            category = AppCategory.UTILITY,
            tip = "Use browserOpen for web. Use openGoogleSearch(query) for search.",
        ),
        AppProfile(
            packageName = "com.google.android.dialer",
            displayName = "Phone",
            category = AppCategory.UTILITY,
            shortcutTool = "makePhoneCall(number)",
            shortcutDesc = "Make a phone call. 1 call.",
            tip = "Use makePhoneCall(num) — 1 call.",
        ),

        // === PRODUCTIVITY ===
        AppProfile(
            packageName = "com.google.android.keep",
            displayName = "Google Keep",
            category = AppCategory.PRODUCTIVITY,
            tip = "launchApp + tap(+FAB) → type → auto-save.",
        ),
        AppProfile(
            packageName = "com.google.android.calendar",
            displayName = "Calendar",
            category = AppCategory.PRODUCTIVITY,
            shortcutTool = "createCalendarEvent(title, start, duration)",
            shortcutDesc = "Create a calendar event. 1 call.",
            tip = "Use createCalendarEvent — 1 call.",
        ),
    )

    // Cached profile list (with installed/enabled state)
    @Volatile
    private var cachedProfiles: List<AppProfile>? = null

    /**
     * Refresh the installed state of all built-in profiles by querying PackageManager.
     * Call this on first launch or when user clicks "Scan for new apps".
     */
    fun refreshInstalled(context: Context) {
        val pm = context.packageManager
        val installedProfiles = BUILTIN_PROFILES.map { builtin ->
            val isInstalled = try {
                pm.getPackageInfo(builtin.packageName, 0)
                true
            } catch (e: PackageManager.NameNotFoundException) {
                false
            }
            // Load saved enabled state (default = installed state, i.e. auto-enable on first detect)
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val savedEnabled = prefs.getBoolean(KEY_ENABLED_PREFIX + builtin.packageName, isInstalled)
            builtin.copy(installed = isInstalled, enabled = savedEnabled)
        }
        // Add user-installed custom profiles
        val customs = loadCustomProfiles(context)
        cachedProfiles = installedProfiles + customs
        Log.i(TAG, "Refreshed: ${installedProfiles.count { it.installed }} installed of ${installedProfiles.size} built-ins + ${customs.size} custom")
    }

    /**
     * Refresh + return all profiles (for UI).
     */
    fun refreshAndGet(context: Context): List<AppProfile> {
        refreshInstalled(context)
        return cachedProfiles ?: emptyList()
    }

    /**
     * Get all profiles (uses cache, refreshes if cold).
     */
    fun getAllProfiles(context: Context): List<AppProfile> {
        cachedProfiles?.let { return it }
        refreshInstalled(context)
        return cachedProfiles ?: emptyList()
    }

    /**
     * Get only installed + enabled profiles (for prompt injection).
     */
    fun getActiveProfiles(context: Context): List<AppProfile> {
        return getAllProfiles(context).filter { it.installed && it.enabled }
    }

    /**
     * Toggle a profile's enabled state.
     */
    fun setEnabled(context: Context, packageName: String, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED_PREFIX + packageName, enabled).apply()
        cachedProfiles?.find { it.packageName == packageName }?.enabled = enabled
        Log.i(TAG, "App '$packageName' ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Add a user-defined custom profile (not in built-in list).
     * Used for "Add custom app" feature in UI.
     */
    fun addCustomProfile(context: Context, profile: AppProfile) {
        val dir = File(context.filesDir, CUSTOM_DIR)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${profile.packageName}.json")
        val json = JSONObject().apply {
            put("packageName", profile.packageName)
            put("displayName", profile.displayName)
            put("category", profile.category.name)
            put("tip", profile.tip ?: "")
        }
        file.writeText(json.toString())
        cachedProfiles = null // invalidate cache
        Log.i(TAG, "Added custom profile: ${profile.packageName}")
    }

    /**
     * Remove a user-defined custom profile.
     */
    fun removeCustomProfile(context: Context, packageName: String) {
        val file = File(context.filesDir, "$CUSTOM_DIR/$packageName.json")
        if (file.exists()) {
            file.delete()
            cachedProfiles = null
            Log.i(TAG, "Removed custom profile: $packageName")
        }
    }

    /**
     * Load user-defined custom profiles from filesDir.
     */
    private fun loadCustomProfiles(context: Context): List<AppProfile> {
        val dir = File(context.filesDir, CUSTOM_DIR)
        if (!dir.exists()) return emptyList()
        val result = mutableListOf<AppProfile>()
        dir.listFiles()?.forEach { file ->
            if (file.extension != "json") return@forEach
            try {
                val json = JSONObject(file.readText())
                val pkg = json.optString("packageName", "")
                if (pkg.isEmpty()) return@forEach
                val name = json.optString("displayName", pkg)
                val cat = try {
                    AppCategory.valueOf(json.optString("category", "UTILITY"))
                } catch (e: Exception) {
                    AppCategory.UTILITY
                }
                val tip = json.optString("tip", "").ifBlank { null }
                // Detect installed state
                val pm = context.packageManager
                val isInstalled = try {
                    pm.getPackageInfo(pkg, 0); true
                } catch (e: PackageManager.NameNotFoundException) {
                    false
                }
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val enabled = prefs.getBoolean(KEY_ENABLED_PREFIX + pkg, isInstalled)
                result.add(AppProfile(pkg, name, cat, null, null, null, tip, isInstalled, enabled))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load custom profile: ${file.name}", e)
            }
        }
        return result
    }

    /**
     * Build the prompt section to inject into the system prompt.
     * Lists only installed + enabled apps with their shortcut tool + tip.
     *
     * Output example:
     *
     * ## Installed Apps (AI-enabled)
     *
     * ### Messaging
     * - WhatsApp: use openWhatsAppChat(phone, msg?) — 1 call. Search at tap(970,200).
     * - Gmail: use composeEmail(to, subject, body) — 1 call.
     *
     * ### Social
     * - YouTube: use searchInApp("com.google.android.youtube", query). Search at tap(970,180).
     *
     * ### Media
     * - Spotify: use playSpotify(query) — 1 call.
     */
    fun getActiveAppsPromptSection(context: Context): String {
        val active = getActiveProfiles(context)
        if (active.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n\n## Installed Apps (AI-enabled)\n")
        sb.append("The following apps are installed on this device and the user has enabled AI access. ")
        sb.append("USE the shortcut tool when available — saves 4-5 API calls per task.\n\n")

        // Group by category for readability
        AppCategory.values().forEach { cat ->
            val inCat = active.filter { it.category == cat }
            if (inCat.isEmpty()) return@forEach
            sb.append("### ${cat.name.lowercase().replaceFirstChar { it.uppercase() }}\n")
            for (app in inCat) {
                sb.append("- ${app.displayName}")
                if (app.shortcutTool != null) {
                    sb.append(": use ${app.shortcutTool}")
                    if (app.shortcutDesc != null) sb.append(" — ${app.shortcutDesc}")
                }
                if (app.tip != null) {
                    sb.append(" ${app.tip}")
                }
                sb.append("\n")
            }
            sb.append("\n")
        }

        sb.append("### When user asks for an app NOT in this list:\n")
        sb.append("- Use launchApp(packageName) generically. The user can enable AI access in Hub → Apps.\n")

        return sb.toString()
    }

    /**
     * Status report for debugging.
     */
    fun getStatusReport(context: Context): String {
        val all = getAllProfiles(context)
        val installed = all.filter { it.installed }
        val enabled = all.filter { it.installed && it.enabled }

        return buildString {
            append("=== App Profile Status ===\n\n")
            append("Total profiles: ${all.size}\n")
            append("Installed: ${installed.size}\n")
            append("AI-enabled: ${enabled.size}\n\n")
            for (p in all) {
                val status = when {
                    !p.installed -> "[NOT INSTALLED]"
                    p.enabled -> "[OK]"
                    else -> "[DISABLED]"
                }
                append("$status ${p.displayName} (${p.category})\n")
                append("   pkg: ${p.packageName}\n")
                if (p.shortcutTool != null) append("   shortcut: ${p.shortcutTool}\n")
                if (p.tip != null) append("   tip: ${p.tip.take(80)}\n")
            }
        }
    }
}
