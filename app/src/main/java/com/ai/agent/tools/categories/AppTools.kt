package com.ai.agent.tools.categories

import android.content.Context
import android.content.pm.PackageManager
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import kotlinx.coroutines.delay

/**
 * App tools category — extracted from ToolExecutor in v3.3.13.
 *
 * Tools: launchApp, listInstalledApps, getAppInfo, forceStopApp, uninstallApp,
 * openSettings, openAppSettings, openActivity, openDeepLink, openAppWithData,
 * searchInApp, listAppActivities, openWhatsAppChat, openYouTubeVideo,
 * openMapsLocation, playSpotify, openInstagramProfile, openTelegramChat,
 * openGoogleSearch, shareToApp, shareText, shareFile.
 *
 * Includes the helpers getInstalledApps() and getCurrentForegroundPackage()
 * (moved verbatim from ToolExecutor.kt).
 *
 * No behavior changes — code moved verbatim from ToolExecutor.kt.
 */
object AppTools {

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "launchApp" -> {
                val pkg = (call.args["package"] as? String ?: call.args["pkg"] as? String)
                    ?: return ToolExecutor.ToolResult(false, "missing package")
                val launched = service!!.launchApp(pkg)
                if (!launched) return ToolExecutor.ToolResult(false, "launchApp($pkg) failed — startActivity returned false")
                // Wait for the app to actually come to foreground before returning.
                // Without this, the next tool (e.g., type) runs against the PREVIOUS foreground app
                // (often the AI Agent's own UI), which is the source of the "BLACKPINK got typed
                // into chat" bug.
                delay(2500)  // give the home launcher + target app time to swap
                // Return FULL screen text (not truncated) so the LLM can see actual UI elements
                // (button labels, icons' content descriptions, menu items) and decide what to click.
                // Capped at 3000 chars to keep the LLM context manageable.
                val screenText = try { service!!.readScreen() } catch (e: Exception) { "(readScreen failed: ${e.message})" }
                val finalScreen = if (screenText.length > 1500) screenText.take(1500) + "\n...[truncated]" else screenText
                ToolExecutor.ToolResult(
                    true,
                    "launchApp($pkg) — launched. After 2.5s, screen content:\n\n$finalScreen"
                )
            }
            "listInstalledApps" -> ToolExecutor.ToolResult(true, getInstalledApps(context))
            "getAppInfo" -> {
                val pkg = call.args["package"] as? String ?: return ToolExecutor.ToolResult(false, "missing package")
                try {
                    val pm = context.packageManager
                    val info = pm.getPackageInfo(pkg, 0)
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val name = pm.getApplicationLabel(appInfo).toString()
                    ToolExecutor.ToolResult(true, "App: $name\nPackage: $pkg\nVersion: ${info.versionName}\nInstalled: ${java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(info.firstInstallTime))}")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "App not found: $pkg") }
            }
            "forceStopApp" -> {
                val pkg = call.args["package"] as? String ?: return ToolExecutor.ToolResult(false, "missing package")
                try {
                    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    am.killBackgroundProcesses(pkg)
                    ToolExecutor.ToolResult(true, "Force stopped $pkg")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Error: ${e.message}") }
            }
            "uninstallApp" -> {
                val pkg = call.args["package"] as? String ?: return ToolExecutor.ToolResult(false, "missing package")
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_DELETE).apply {
                        data = android.net.Uri.parse("package:$pkg")
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    ToolExecutor.ToolResult(true, "Opening uninstall dialog for $pkg")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Error: ${e.message}") }
            }
            // === SHARE TEXT ===
            "shareText" -> {
                val text = call.args["text"] as? String ?: return ToolExecutor.ToolResult(false, "missing text")
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, text)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Share via").apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    ToolExecutor.ToolResult(true, "Share dialog opened")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Share error: ${e.message}") }
            }
            // === SHARE FILE ===
            "shareFile" -> {
                val path = call.args["path"] as? String ?: return ToolExecutor.ToolResult(false, "missing path")
                val mimeType = call.args["mimeType"] as? String ?: "*/*"
                try {
                    val file = java.io.File(path)
                    if (!file.exists()) return ToolExecutor.ToolResult(false, "file not found: $path")
                    // Use FileProvider for secure sharing (Android 7+)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context,
                        context.packageName + ".fileprovider",
                        file
                    )
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, "Share file via").apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                    ToolExecutor.ToolResult(true, "File share dialog opened: $path")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Share file error: ${e.message}") }
            }
            // === OPEN SETTINGS PAGE (deep-link) ===
            "openSettings" -> {
                val page = call.args["page"] as? String ?: "main"
                try {
                    val intent = when (page.lowercase()) {
                        "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS
                        "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                        "location" -> android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                        "accessibility" -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                        "notification" -> android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                        "apps", "applications" -> android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
                        "display", "brightness" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
                        "sound", "volume" -> android.provider.Settings.ACTION_SOUND_SETTINGS
                        "battery" -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
                        "storage" -> android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                        "datetime", "date", "time" -> android.provider.Settings.ACTION_DATE_SETTINGS
                        "language" -> android.provider.Settings.ACTION_LOCALE_SETTINGS
                        "developer" -> android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
                        "about", "device" -> android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS
                        else -> android.provider.Settings.ACTION_SETTINGS  // main settings
                    }.let { action -> android.content.Intent(action) }
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    ToolExecutor.ToolResult(true, "Opened settings: $page")
                } catch (e: Exception) { ToolExecutor.ToolResult(false, "Settings error: ${e.message}") }
            }
            "openAppSettings" -> {
                val pkg = (call.args["package"] as? String ?: call.args["pkg"] as? String)
                    ?: return ToolExecutor.ToolResult(false, "missing package")
                try {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$pkg"))
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    delay(1500)
                    ToolExecutor.ToolResult(true, "Opened app settings for $pkg")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "openAppSettings($pkg) failed: ${e.message}")
                }
            }
            // === ACTIVITY TOOLS (Open specific screens inside other apps) ===

            "openActivity" -> {
                val pkg = (call.args["package"] as? String ?: call.args["pkg"] as? String)
                    ?: return ToolExecutor.ToolResult(false, "missing package")
                val activity = call.args["activity"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing activity (full class name)")
                try {
                    val intent = android.content.Intent()
                    intent.setClassName(pkg, activity)
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    delay(1500)
                    val currentPkg = getCurrentForegroundPackage(context)
                    ToolExecutor.ToolResult(currentPkg == pkg,
                        if (currentPkg == pkg) "Opened $activity (foreground: $pkg)"
                        else "Sent intent to $activity but foreground is $currentPkg")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "openActivity($pkg/$activity) failed: ${e.message}")
                }
            }

            "openDeepLink" -> {
                val uri = call.args["uri"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing uri")
                val pkg = (call.args["package"] as? String ?: call.args["pkg"] as? String)
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    if (pkg != null) intent.setPackage(pkg)
                    context.startActivity(intent)
                    delay(1500)
                    ToolExecutor.ToolResult(true, "Opened deep link: $uri" + (if (pkg != null) " in $pkg" else ""))
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "openDeepLink($uri) failed: ${e.message}. App may not be installed or URI invalid.")
                }
            }

            "openAppWithData" -> {
                val pkg = (call.args["package"] as? String ?: call.args["pkg"] as? String)
                    ?: return ToolExecutor.ToolResult(false, "missing package")
                val uri = call.args["uri"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing uri")
                val action = (call.args["action"] as? String) ?: android.content.Intent.ACTION_VIEW
                val mimeType = call.args["mimeType"] as? String
                try {
                    val intent = android.content.Intent(action, android.net.Uri.parse(uri))
                    intent.setPackage(pkg)
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    if (mimeType != null) intent.type = mimeType
                    context.startActivity(intent)
                    delay(1500)
                    val currentPkg = getCurrentForegroundPackage(context)
                    ToolExecutor.ToolResult(currentPkg == pkg,
                        "Opened $pkg with data $uri (foreground: $currentPkg)")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "openAppWithData($pkg, $uri) failed: ${e.message}")
                }
            }

            "searchInApp" -> {
                val pkg = (call.args["package"] as? String ?: call.args["pkg"] as? String)
                    ?: return ToolExecutor.ToolResult(false, "missing package")
                val query = call.args["query"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing query")
                try {
                    val searchIntent = when (pkg) {
                        "com.google.android.youtube" -> {
                            android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.youtube.com/results?search_query=${java.net.URLEncoder.encode(query, "UTF-8")}"))
                                .setPackage(pkg)
                        }
                        "com.spotify.music" -> {
                            android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("spotify:search:${java.net.URLEncoder.encode(query, "UTF-8")}"))
                                .setPackage(pkg)
                        }
                        "com.instagram.android" -> {
                            android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.instagram.com/explore/tags/${java.net.URLEncoder.encode(query, "UTF-8")}"))
                                .setPackage(pkg)
                        }
                        "com.android.chrome" -> {
                            android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"))
                                .setPackage(pkg)
                        }
                        else -> {
                            context.packageManager.getLaunchIntentForPackage(pkg)
                                ?: return ToolExecutor.ToolResult(false, "App $pkg not installed")
                        }
                    }
                    searchIntent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(searchIntent)
                    delay(2000)
                    // v3.8.2: Include a hint in the result so the brain knows what to do next.
                    // Without this, the brain was opening Chrome/launchApp after searchInApp succeeded.
                    val hint = when (pkg) {
                        "com.google.android.youtube" -> " YouTube app is now OPEN with search results on screen. Do NOT open browser, openInChrome, openUrl, or launchApp. The results are already showing. Next step: readScreenStructured() to find a video result's bounds, then tap(center of bounds) to play it."
                        "com.spotify.music" -> " Spotify is now OPEN with search results. Do NOT open browser. Next step: readScreen() to see what's playing."
                        "com.instagram.android" -> " Instagram is now OPEN with the tag/search results. Do NOT open browser. Next step: readScreen() to see the results."
                        else -> " App is now open. Do NOT open browser or launchApp again. Next step: readScreen() to see the current state."
                    }
                    ToolExecutor.ToolResult(true, "Searched '$query' in $pkg.$hint")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "searchInApp($pkg, $query) failed: ${e.message}")
                }
            }

            "openWhatsAppChat" -> {
                val phone = call.args["phone"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing phone (international format, e.g. +919001234567)")
                val message = call.args["message"] as? String
                try {
                    val uri = if (message != null) {
                        "https://wa.me/$phone?text=${java.net.URLEncoder.encode(message, "UTF-8")}"
                    } else {
                        "https://wa.me/$phone"
                    }
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                    intent.setPackage("com.whatsapp")
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    delay(2500)  // v3.3.10: wait for WhatsApp to load the chat
                    // v3.3.10: if a message was pre-filled, auto-click the Send button.
                    // This makes openWhatsAppChat a true 1-call send — the AI doesn't
                    // need to follow up with clickByText("Send") manually.
                    var sent = false
                    if (message != null) {
                        try {
                            val a11y = com.ai.agent.accessibility.AgentAccessibilityService.getInstance()
                            if (a11y != null) {
                                // Try common Send button texts across WhatsApp versions.
                                val sendTexts = listOf("Send", "SEND", "send", "➤", "Send message")
                                for (txt in sendTexts) {
                                    if (a11y.clickByText(txt)) { sent = true; break }
                                }
                                // If clickByText failed, try tapping the send button by position.
                                // WhatsApp's send button is typically at the bottom-right.
                                if (!sent) {
                                    // Try clicking the send FAB via content description.
                                    // (varies by WhatsApp version — sometimes "Send", sometimes an icon)
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("ToolExecutor", "Auto-send failed: ${e.message}")
                        }
                    }
                    val msg = if (sent) "Opened WhatsApp chat with $phone + auto-sent message ✓"
                              else if (message != null) "Opened WhatsApp chat with $phone (message pre-filled — tap Send manually or call clickByText(\"Send\"))"
                              else "Opened WhatsApp chat with $phone"
                    ToolExecutor.ToolResult(true, msg)
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "openWhatsAppChat($phone) failed: ${e.message}. WhatsApp may not be installed.")
                }
            }

            "openYouTubeVideo" -> {
                // v3.8.2: Accept both 'videoId' and 'id' params — brain sometimes guesses 'id'.
                val videoId = (call.args["videoId"] as? String ?: call.args["id"] as? String)
                    ?: return ToolExecutor.ToolResult(false, "missing videoId. Pass {videoId: \"<11-char ID>\"} or {id: \"<11-char ID>\"}.")
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("vnd.youtube://video/$videoId"))
                    intent.setPackage("com.google.android.youtube")
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    delay(2000)
                    ToolExecutor.ToolResult(true, "Opened YouTube video: $videoId")
                } catch (e: Exception) {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                        delay(2000)
                        ToolExecutor.ToolResult(true, "Opened YouTube video $videoId in browser (YouTube app not available)")
                    } catch (e2: Exception) {
                        ToolExecutor.ToolResult(false, "openYouTubeVideo($videoId) failed: ${e2.message}")
                    }
                }
            }

            "openMapsLocation" -> {
                val query = call.args["query"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing query")
                val lat = call.args["lat"] as? Number
                val lng = call.args["lng"] as? Number
                try {
                    val uri = if (lat != null && lng != null) {
                        "geo:${lat},${lng}?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                    } else {
                        "geo:0,0?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
                    }
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                    intent.setPackage("com.google.android.apps.maps")
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    delay(2000)
                    ToolExecutor.ToolResult(true, "Opened Maps: $query")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "openMapsLocation($query) failed: ${e.message}. Google Maps may not be installed.")
                }
            }

            "listAppActivities" -> {
                val pkg = (call.args["package"] as? String ?: call.args["pkg"] as? String)
                    ?: return ToolExecutor.ToolResult(false, "missing package")
                try {
                    val pm = context.packageManager
                    val packageInfo = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES)
                    val activities = packageInfo.activities?.mapIndexed { i, info ->
                        val exported = if (info.exported) " [exported]" else " [internal]"
                        val name = info.name.substringAfterLast('.')
                        "  ${i+1}. $name$exported"
                    }?.joinToString("\n") ?: "No activities found"
                    ToolExecutor.ToolResult(true, "Activities in $pkg:\n$activities")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "listAppActivities($pkg) failed: ${e.message}. App may not be installed.")
                }
            }

            "playSpotify" -> {
                val query = call.args["query"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing query (song/artist name)")
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("spotify:search:${java.net.URLEncoder.encode(query, "UTF-8")}"))
                    intent.setPackage("com.spotify.music")
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    delay(2500)
                    ToolExecutor.ToolResult(true, "Opened Spotify search for: $query. Tap the first result to play.")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "playSpotify($query) failed: ${e.message}. Spotify may not be installed.")
                }
            }

            "openInstagramProfile" -> {
                val username = call.args["username"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing username")
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://www.instagram.com/$username/"))
                    intent.setPackage("com.instagram.android")
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    delay(2000)
                    ToolExecutor.ToolResult(true, "Opened Instagram profile: @$username")
                } catch (e: Exception) {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.instagram.com/$username/"))
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                        delay(2000)
                        ToolExecutor.ToolResult(true, "Opened Instagram profile @$username in browser (app not installed)")
                    } catch (e2: Exception) {
                        ToolExecutor.ToolResult(false, "openInstagramProfile($username) failed: ${e2.message}")
                    }
                }
            }

            "openTelegramChat" -> {
                val username = call.args["username"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing username")
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://t.me/$username"))
                    intent.setPackage("org.telegram.messenger")
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    delay(2000)
                    ToolExecutor.ToolResult(true, "Opened Telegram chat with @$username")
                } catch (e: Exception) {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://t.me/$username"))
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                        delay(2000)
                        ToolExecutor.ToolResult(true, "Opened Telegram @$username in browser (app not installed)")
                    } catch (e2: Exception) {
                        ToolExecutor.ToolResult(false, "openTelegramChat($username) failed: ${e2.message}")
                    }
                }
            }

            "openGoogleSearch" -> {
                val query = call.args["query"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing query")
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_WEB_SEARCH)
                    intent.putExtra("query", query)
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                    delay(1500)
                    ToolExecutor.ToolResult(true, "Google search: $query")
                } catch (e: Exception) {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"))
                        intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                        delay(1500)
                        ToolExecutor.ToolResult(true, "Opened Google search: $query (in browser)")
                    } catch (e2: Exception) {
                        ToolExecutor.ToolResult(false, "openGoogleSearch($query) failed: ${e2.message}")
                    }
                }
            }

            "shareToApp" -> {
                val pkg = (call.args["package"] as? String ?: call.args["pkg"] as? String)
                    ?: return ToolExecutor.ToolResult(false, "missing package")
                val text = call.args["text"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing text")
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND)
                    intent.type = "text/plain"
                    intent.putExtra(android.content.Intent.EXTRA_TEXT, text)
                    intent.setPackage(pkg)
                    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(android.content.Intent.createChooser(intent, "Share to").apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    })
                    delay(1500)
                    ToolExecutor.ToolResult(true, "Shared text to $pkg")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "shareToApp($pkg) failed: ${e.message}. App may not support text sharing.")
                }
            }

            else -> ToolExecutor.ToolResult(false, "Unknown app tool: ${call.name}")
        }
    }

    private fun getCurrentForegroundPackage(context: Context): String {
        return try {
            val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            // For Android 5.0+ use UsageStatsManager (requires PACKAGE_USAGE_STATS permission)
            // For now, use the accessibility service's tracked package
            val service = AgentAccessibilityService.getInstance()
            if (service != null) {
                service.getCurrentForegroundPackage() ?: "unknown"
            } else {
                "unknown"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getInstalledApps(context: Context): String {
        return try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val thirdParty = packages
                .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
                .joinToString("\n") { "package:${it.packageName}" }
            "Third-party apps (${packages.size} total):\n$thirdParty"
        } catch (e: Exception) {
            "Error listing apps: ${e.message}"
        }
    }
}
