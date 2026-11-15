package com.ai.agent.ui.screens.hub

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily

// ============================================================================
// v3.6.0: Permissions Page Redesign — 3-tier structure with explanations,
// tool mappings, MIUI-specific guidance, and 2 new critical permissions
// (Notification Listener + Battery Optimization).
//
// Tiers:
//   REQUIRED               — brain won't work without
//   CRITICAL_FOR_BACKGROUND — rules/background tasks won't fire without
//   OPTIONAL               — specific tools won't work without
// ============================================================================

enum class PermissionTier(val label: String, val icon: String) {
    REQUIRED("Required", "⚠️"),
    CRITICAL_FOR_BACKGROUND("Critical for Background", "🔋"),
    OPTIONAL("Optional", "🔧"),
}

enum class PermType {
    RUNTIME, OVERLAY, ALL_FILES, ACCESSIBILITY, INTERNET,
    NOTIFICATION_LISTENER, BATTERY_OPTIMIZATION, AUTO_START_MIUI
}

data class PermEntry(
    val id: String,
    val title: String,
    val tier: PermissionTier,
    val type: PermType,
    val why: String,
    val tools: List<String>,
    val miuiNote: String? = null,
)

private val ALL_PERMISSIONS = listOf(
    // === REQUIRED (3) ===
    PermEntry(
        id = "__accessibility__",
        title = "Accessibility Service",
        tier = PermissionTier.REQUIRED,
        type = PermType.ACCESSIBILITY,
        why = "Lets the brain tap, type, read screen, take screenshots. Without this, brain is blind.",
        tools = listOf("tap", "clickByText", "type", "swipe", "scrollDown", "readScreen", "takeScreenshot"),
        miuiNote = "On MIUI: Settings → Additional Settings → Accessibility → Aira → ON. Accept the MIUI warning.",
    ),
    PermEntry(
        id = Manifest.permission.SYSTEM_ALERT_WINDOW,
        title = "Display Over Other Apps",
        tier = PermissionTier.REQUIRED,
        type = PermType.OVERLAY,
        why = "Shows the floating button + voice input overlay.",
        tools = listOf("Floating button", "Voice input"),
        miuiNote = "On MIUI: also enable 'Display pop-up windows while running in background' in Settings → Apps → Aira → Permissions.",
    ),
    PermEntry(
        id = Manifest.permission.INTERNET,
        title = "Internet",
        tier = PermissionTier.REQUIRED,
        type = PermType.INTERNET,
        why = "Calls the AI API. Brain can't think without it.",
        tools = listOf("All LLM calls"),
    ),

    // === CRITICAL FOR BACKGROUND (2) ===
    PermEntry(
        id = "__notification_listener__",
        title = "Notification Listener",
        tier = PermissionTier.CRITICAL_FOR_BACKGROUND,
        type = PermType.NOTIFICATION_LISTENER,
        why = "Reads WhatsApp/SMS notifications + enables auto-reply rules. Without this, rules don't fire.",
        tools = listOf("Auto-reply rules", "NotificationListener"),
        miuiNote = "MIUI sometimes auto-revokes this. If auto-reply stops working, re-check this permission.",
    ),
    PermEntry(
        id = "__battery_optimization__",
        title = "Battery Optimization Exemption",
        tier = PermissionTier.CRITICAL_FOR_BACKGROUND,
        type = PermType.BATTERY_OPTIMIZATION,
        why = "Stops MIUI/HyperOS from killing the brain in background. Without this, rules won't fire when screen is off.",
        tools = listOf("All background tasks", "Scheduled rules"),
        miuiNote = "On MIUI: ALSO enable Auto-start in Settings → Apps → Aira → Auto-start → ON.",
    ),

    // === OPTIONAL (12) ===
    // v3.5.4: On Android 13+ (API 33+), READ_EXTERNAL_STORAGE does nothing.
    // Use READ_MEDIA_IMAGES/VIDEO/AUDIO instead. On older Android, use the legacy one.
    PermEntry(
        id = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                "android.permission.READ_MEDIA_IMAGES"
             else Manifest.permission.READ_EXTERNAL_STORAGE,
        title = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                "Read Media (Images)" else "Read External Storage",
        tier = PermissionTier.OPTIONAL,
        type = PermType.RUNTIME,
        why = "Read files, photos, and documents from storage.",
        tools = listOf("readFile", "listFiles", "readAttachment", "takePhoto gallery access"),
    ),
    PermEntry(
        id = Manifest.permission.MANAGE_EXTERNAL_STORAGE,
        title = "All Files Access",
        tier = PermissionTier.OPTIONAL,
        type = PermType.ALL_FILES,
        why = "Full file management anywhere on device.",
        tools = listOf("writeFile", "copyFile", "moveFile", "deleteFile"),
    ),
    PermEntry(
        id = Manifest.permission.RECORD_AUDIO,
        title = "Microphone",
        tier = PermissionTier.OPTIONAL,
        type = PermType.RUNTIME,
        why = "Voice input + speech recognition.",
        tools = listOf("Voice input", "ASR"),
    ),
    PermEntry(
        id = Manifest.permission.CAMERA,
        title = "Camera",
        tier = PermissionTier.OPTIONAL,
        type = PermType.RUNTIME,
        why = "Take photos with the camera.",
        tools = listOf("takePhoto"),
    ),
    PermEntry(
        id = Manifest.permission.READ_CONTACTS,
        title = "Read Contacts",
        tier = PermissionTier.OPTIONAL,
        type = PermType.RUNTIME,
        why = "Access your contacts list.",
        tools = listOf("getContacts", "openContact", "openWhatsAppChat"),
    ),
    PermEntry(
        id = Manifest.permission.CALL_PHONE,
        title = "Call Phone",
        tier = PermissionTier.OPTIONAL,
        type = PermType.RUNTIME,
        why = "Make phone calls.",
        tools = listOf("callContact", "makePhoneCall"),
    ),
    PermEntry(
        id = Manifest.permission.SEND_SMS,
        title = "Send SMS",
        tier = PermissionTier.OPTIONAL,
        type = PermType.RUNTIME,
        why = "Send SMS messages on your behalf.",
        tools = listOf("sendSMS"),
    ),
    PermEntry(
        id = Manifest.permission.ACCESS_FINE_LOCATION,
        title = "Fine Location",
        tier = PermissionTier.OPTIONAL,
        type = PermType.RUNTIME,
        why = "Get your GPS location.",
        tools = listOf("currentLocation", "getWeather", "openMapsLocation"),
    ),
    PermEntry(
        id = Manifest.permission.READ_CALENDAR,
        title = "Read Calendar",
        tier = PermissionTier.OPTIONAL,
        type = PermType.RUNTIME,
        why = "Access your calendar events.",
        tools = listOf("getCalendarEvents"),
    ),
    PermEntry(
        id = Manifest.permission.POST_NOTIFICATIONS,
        title = "Post Notifications",
        tier = PermissionTier.OPTIONAL,
        type = PermType.RUNTIME,
        why = "Shows the brain's persistent foreground notification.",
        tools = listOf("Foreground service notification"),
    ),
    PermEntry(
        id = "__auto_start_miui__",
        title = "Auto-start (MIUI only)",
        tier = PermissionTier.OPTIONAL,
        type = PermType.AUTO_START_MIUI,
        why = "Allows the brain to start automatically on boot/reboot.",
        tools = listOf("BootReceiver", "Scheduled rule triggers"),
        miuiNote = "MIUI only: Settings → Apps → Aira → Auto-start → ON. Not needed on non-MIUI devices.",
    ),
)

/**
 * Check if a permission is granted.
 */
fun isPermissionGranted(context: Context, entry: PermEntry): Boolean {
    return when (entry.type) {
        PermType.RUNTIME -> ContextCompat.checkSelfPermission(context, entry.id) == PackageManager.PERMISSION_GRANTED
        PermType.OVERLAY -> Settings.canDrawOverlays(context)
        PermType.ALL_FILES -> android.os.Environment.isExternalStorageManager()
        PermType.INTERNET -> true
        PermType.ACCESSIBILITY -> isAccessibilityEnabled(context)
        PermType.NOTIFICATION_LISTENER -> isNotificationListenerEnabled(context)
        PermType.BATTERY_OPTIMIZATION -> {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        }
        PermType.AUTO_START_MIUI -> {
            // Can't check programmatically — always show as "check manually"
            // The user needs to verify in MIUI settings
            true // assume granted, show MIUI note for manual check
        }
    }
}

fun isAccessibilityEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/com.ai.agent.accessibility.AgentAccessibilityService"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"  // Wrong key — fix below
    ) ?: ""
    // Actually use the correct key for accessibility
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: ""
    return enabledServices.contains(expected)
}

fun isNotificationListenerEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/com.ai.agent.rules.NotificationListener"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        "enabled_notification_listeners"
    ) ?: ""
    return enabled.contains(expected)
}

/**
 * Get the intent to grant a permission (for special permissions that need Settings).
 * Returns null for RUNTIME permissions (handled by permission launcher).
 */
fun getGrantIntent(context: Context, entry: PermEntry): Intent? {
    return when (entry.type) {
        PermType.RUNTIME -> null
        PermType.OVERLAY -> Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PermType.ALL_FILES -> Intent(
            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PermType.ACCESSIBILITY -> Intent(
            Settings.ACTION_ACCESSIBILITY_SETTINGS
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PermType.INTERNET -> null
        PermType.NOTIFICATION_LISTENER -> Intent(
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PermType.BATTERY_OPTIMIZATION -> Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        PermType.AUTO_START_MIUI -> {
            // MIUI Auto-start management activity
            Intent().apply {
                setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}

// ============================================================================
// UI — PermissionsSection
// ============================================================================

@Composable
internal fun PermissionsSection() {
    val context = LocalContext.current
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    var refreshKey by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }

    // Runtime permission launcher
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> refreshKey++ }

    // ON_RESUME refresh
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Compute permission states
    val permissionStates = remember(refreshKey) {
        ALL_PERMISSIONS.associateWith { isPermissionGranted(context, it) }
    }
    val grantedCount = permissionStates.count { it.value }
    val totalCount = permissionStates.size

    // Group by tier
    val byTier = ALL_PERMISSIONS.groupBy { it.tier }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // === Status Banner ===
        item {
            val missingRequired = byTier[PermissionTier.REQUIRED]?.count { !permissionStates[it]!! } ?: 0
            val missingCritical = byTier[PermissionTier.CRITICAL_FOR_BACKGROUND]?.count { !permissionStates[it]!! } ?: 0
            val bgColor = if (missingRequired > 0) custom.accent.copy(alpha = 0.08f)
                          else if (missingCritical > 0) Color(0xFFFFA000).copy(alpha = 0.08f)
                          else custom.accent.copy(alpha = 0.04f)
            val borderColor = if (missingRequired > 0) custom.accent.copy(alpha = 0.3f)
                              else if (missingCritical > 0) Color(0xFFFFA000).copy(alpha = 0.3f)
                              else custom.accent.copy(alpha = 0.15f)
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(bgColor, RoundedCornerShape(8.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🛡️", fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("$grantedCount/$totalCount permissions granted",
                        color = m3.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    when {
                        missingRequired > 0 -> Text("⚠️ $missingRequired required permissions missing — brain won't work until granted.",
                            color = custom.fg3, fontSize = 10.sp)
                        missingCritical > 0 -> Text("⚠️ $missingCritical background permissions missing — rules may not fire.",
                            color = custom.fg3, fontSize = 10.sp)
                        else -> Text("✓ All required permissions granted. Brain is fully functional.",
                            color = custom.accent, fontSize = 10.sp)
                    }
                }
            }
        }

        // === Grant All Required Button ===
        item {
            val missingRuntime = ALL_PERMISSIONS
                .filter { it.type == PermType.RUNTIME && !permissionStates[it]!! }
                .map { it.id }
                .toTypedArray()
            OutlinedButton(
                onClick = {
                    if (missingRuntime.isNotEmpty()) {
                        permLauncher.launch(missingRuntime)
                        toast = "Requesting ${missingRuntime.size} runtime permissions..."
                    } else {
                        toast = "All runtime permissions granted. Grant special permissions manually below."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Grant all runtime permissions", fontSize = 12.sp)
            }
        }

        // === Permission Cards by Tier ===
        for (tier in PermissionTier.values()) {
            val entries = byTier[tier] ?: continue
            item {
                Text("${tier.icon} ${tier.label.uppercase()} (${entries.count { permissionStates[it]!! }}/${entries.size})",
                    color = custom.fg2, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            }
            items(entries, key = { it.id }) { entry ->
                PermissionCard(
                    entry = entry,
                    granted = permissionStates[entry] ?: false,
                    onGrant = {
                        when (entry.type) {
                            PermType.RUNTIME -> permLauncher.launch(arrayOf(entry.id))
                            else -> {
                                val intent = getGrantIntent(context, entry)
                                if (intent != null) {
                                    try {
                                        context.startActivity(intent)
                                        toast = "Opening Settings for ${entry.title}..."
                                    } catch (e: Exception) {
                                        toast = "Could not open Settings for ${entry.title} (${e.message})"
                                    }
                                }
                            }
                        }
                    },
                    custom = custom,
                    m3 = m3,
                )
            }
        }

        // === Re-scan Button ===
        item {
            TextButton(
                onClick = { refreshKey++; toast = "Re-scanned permissions" },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Re-scan permissions", fontSize = 11.sp, color = custom.fg3)
            }
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            toast = null
        }
    }
}

@Composable
private fun PermissionCard(
    entry: PermEntry,
    granted: Boolean,
    onGrant: () -> Unit,
    custom: com.ai.agent.ui.components.CustomColors,
    m3: androidx.compose.material3.ColorScheme,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(
                if (granted) custom.accent.copy(alpha = 0.04f) else custom.ink2,
                RoundedCornerShape(8.dp)
            )
            .border(
                1.dp,
                if (granted) custom.accent.copy(alpha = 0.2f) else custom.inkBorder,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // Row 1: status icon + title + grant button
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (granted) "✓" else "✗",
                color = if (granted) custom.accent else custom.fg3,
                fontSize = 14.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.title,
                color = m3.onSurface,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (!granted && entry.type != PermType.INTERNET) {
                TextButton(onClick = onGrant, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("GRANT", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = custom.accent)
                }
            }
        }
        // Row 2: why
        Text(entry.why, color = custom.fg3, fontSize = 9.sp, lineHeight = 12.sp,
            modifier = Modifier.padding(start = 22.dp, top = 2.dp))
        // Row 3: tools
        if (entry.tools.isNotEmpty()) {
            Text("Tools: ${entry.tools.joinToString(", ")}",
                color = custom.fg3, fontSize = 8.sp, fontFamily = MonoFontFamily,
                modifier = Modifier.padding(start = 22.dp, top = 2.dp))
        }
        // Row 4: MIUI note (if present)
        if (entry.miuiNote != null) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 22.dp, top = 4.dp)
                    .background(Color(0xFFFFA000).copy(alpha = 0.06f), RoundedCornerShape(4.dp))
                    .border(1.dp, Color(0xFFFFA000).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text("⚠️ MIUI: ", color = Color(0xFFFFA000), fontSize = 8.sp, fontWeight = FontWeight.Bold)
                Text(entry.miuiNote, color = custom.fg3, fontSize = 8.sp, lineHeight = 10.sp)
            }
        }
    }
}
