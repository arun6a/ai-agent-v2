package com.ai.agent.ui.screens.hub

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Rule
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.ApiUsageTracker
import com.ai.agent.llm.AppProfileManager
import com.ai.agent.llm.ModelSettingsScreen
import com.ai.agent.skills.SkillManager
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import com.ai.agent.ui.components.HubNavItem
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.components.StatCard
import com.ai.agent.ui.theme.MonoFontFamily
import com.ai.agent.ui.theme.ThemeManager
import com.ai.agent.ui.screens.ChatViewModel
import com.ai.agent.ui.screens.isUserCreatedSkill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Hub section: Permissions — runtime + special perms with grant UI.

// ---------------------------------------------------------------------------
// Functional section: Permissions (uses ContextCompat.checkSelfPermission)
// ---------------------------------------------------------------------------
// Issue 53 — full grant UI:
//   • Each row shows a Grant button when not granted
//   • Grant All button at top requests all runtime perms in one batch
//   • Special perms (Overlay / All Files / Accessibility) deep-link into
//     the relevant system Settings screen
//   • ON_RESUME lifecycle observer bumps refreshKey so granted state is
//     re-checked when the user returns from system Settings
// ---------------------------------------------------------------------------

private enum class PermissionType { RUNTIME, OVERLAY, ALL_FILES, ACCESSIBILITY, INTERNET }

private enum class PermissionGroup(val label: String) {
    REQUIRED("Required"),
    STORAGE("Storage"),
    AUDIO_CAMERA("Audio / Camera"),
    CONTACTS_SMS("Contacts / SMS"),
    LOCATION_CALENDAR("Location / Calendar"),
    NOTIFICATIONS("Notifications"),
}

private data class PermissionEntry(
    val perm: String,        // Manifest.permission.* constant (or pseudo-key for special perms)
    val display: String,     // "Camera" / "Microphone" / ...
    val type: PermissionType,
    val group: PermissionGroup,
)

private val PERMISSIONS = listOf(
    // Required
    PermissionEntry("__accessibility__", "Accessibility Service", PermissionType.ACCESSIBILITY, PermissionGroup.REQUIRED),
    PermissionEntry(Manifest.permission.SYSTEM_ALERT_WINDOW, "Display Over Other Apps", PermissionType.OVERLAY, PermissionGroup.REQUIRED),
    PermissionEntry(Manifest.permission.INTERNET, "Internet", PermissionType.INTERNET, PermissionGroup.REQUIRED),
    // Storage
    PermissionEntry(Manifest.permission.READ_EXTERNAL_STORAGE, "Read External Storage", PermissionType.RUNTIME, PermissionGroup.STORAGE),
    PermissionEntry(Manifest.permission.MANAGE_EXTERNAL_STORAGE, "All Files Access", PermissionType.ALL_FILES, PermissionGroup.STORAGE),
    PermissionEntry("android.permission.READ_MEDIA_IMAGES", "Read Media (Images)", PermissionType.RUNTIME, PermissionGroup.STORAGE),
    // Audio / Camera
    PermissionEntry(Manifest.permission.RECORD_AUDIO, "Microphone", PermissionType.RUNTIME, PermissionGroup.AUDIO_CAMERA),
    PermissionEntry(Manifest.permission.CAMERA, "Camera", PermissionType.RUNTIME, PermissionGroup.AUDIO_CAMERA),
    // Contacts / SMS
    PermissionEntry(Manifest.permission.READ_CONTACTS, "Read Contacts", PermissionType.RUNTIME, PermissionGroup.CONTACTS_SMS),
    PermissionEntry(Manifest.permission.CALL_PHONE, "Call Phone", PermissionType.RUNTIME, PermissionGroup.CONTACTS_SMS),
    PermissionEntry(Manifest.permission.SEND_SMS, "Send SMS", PermissionType.RUNTIME, PermissionGroup.CONTACTS_SMS),
    // Location / Calendar
    PermissionEntry(Manifest.permission.ACCESS_FINE_LOCATION, "Fine Location", PermissionType.RUNTIME, PermissionGroup.LOCATION_CALENDAR),
    PermissionEntry(Manifest.permission.READ_CALENDAR, "Read Calendar", PermissionType.RUNTIME, PermissionGroup.LOCATION_CALENDAR),
    // Notifications
    PermissionEntry(Manifest.permission.POST_NOTIFICATIONS, "Post Notifications", PermissionType.RUNTIME, PermissionGroup.NOTIFICATIONS),
)

/**
 * Returns true iff our AgentAccessibilityService is enabled in the system's
 * Accessibility settings. Used to mark the Accessibility permission row as
 * granted / missing in the Hub's Permissions section.
 */
internal fun isAccessibilityEnabled(context: android.content.Context): Boolean {
    val enabled = try {
        android.provider.Settings.Secure.getInt(
            context.contentResolver,
            android.provider.Settings.Secure.ACCESSIBILITY_ENABLED,
            0,
        ) == 1
    } catch (_: Exception) {
        false
    }
    if (!enabled) return false
    val expected = "${context.packageName}/com.ai.agent.accessibility.AgentAccessibilityService"
    val list = try {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
    } catch (_: Exception) {
        null
    } ?: return false
    val splitter = android.text.TextUtils.SimpleStringSplitter(':').apply { setString(list) }
    while (splitter.hasNext()) {
        if (splitter.next().equals(expected, ignoreCase = true)) return true
    }
    return false
}

@Composable
internal fun PermissionsSection() {
    val context = LocalContext.current
    val custom = LocalCustomColors.current

    // refreshKey — bumped when runtime-permission result arrives OR on ON_RESUME.
    // Re-checks the granted state of every permission (incl. special perms that
    // can only be granted via system Settings → Accessibility / Overlay / All Files).
    var refreshKey by remember { mutableStateOf(0) }

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> refreshKey++ }

    // Bump refreshKey whenever the user comes back from system Settings (where
    // overlay, all-files, and accessibility permissions are granted).
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Compute granted state for every permission — memoized on refreshKey so a
    // state change triggers a single re-evaluation per refresh.
    val permissionStates = remember(refreshKey) {
        PERMISSIONS.associate { entry ->
            val granted = when (entry.type) {
                PermissionType.RUNTIME -> ContextCompat.checkSelfPermission(context, entry.perm) ==
                    PackageManager.PERMISSION_GRANTED
                PermissionType.OVERLAY -> android.provider.Settings.canDrawOverlays(context)
                PermissionType.ALL_FILES -> android.os.Environment.isExternalStorageManager()
                PermissionType.INTERNET -> true  // auto-granted on install
                PermissionType.ACCESSIBILITY -> isAccessibilityEnabled(context)
            }
            entry.perm to granted
        }
    }

    val grantedCount = permissionStates.count { it.value }
    val total = permissionStates.size

    fun requestPermission(entry: PermissionEntry) {
        when (entry.type) {
            PermissionType.RUNTIME -> permLauncher.launch(arrayOf(entry.perm))
            PermissionType.OVERLAY -> {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}"),
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (_: Exception) {}
            }
            PermissionType.ALL_FILES -> {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (_: Exception) {}
            }
            PermissionType.ACCESSIBILITY -> {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                try { context.startActivity(intent) } catch (_: Exception) {}
            }
            PermissionType.INTERNET -> {}  // always granted
        }
    }

    fun grantAll() {
        val allRuntimePerms = PERMISSIONS
            .filter { it.type == PermissionType.RUNTIME }
            .map { it.perm }
            .toTypedArray()
        if (allRuntimePerms.isNotEmpty()) permLauncher.launch(allRuntimePerms)
    }

    // Group permission entries by their PermissionGroup, preserving declared order.
    val grouped = PERMISSIONS.groupBy { it.group }

    // Critical permissions — if any of these is missing the agent can't run.
    // Matches MainActivity.checkCriticalPermissions() plus the special perms
    // (Accessibility + Overlay) which gate the floating button + screen-reading.
    val missingCritical = remember(refreshKey) {
        PERMISSIONS.filter { entry ->
            val granted = when (entry.type) {
                PermissionType.RUNTIME -> ContextCompat.checkSelfPermission(context, entry.perm) ==
                    PackageManager.PERMISSION_GRANTED
                PermissionType.OVERLAY -> android.provider.Settings.canDrawOverlays(context)
                PermissionType.ALL_FILES -> android.os.Environment.isExternalStorageManager()
                PermissionType.INTERNET -> true
                PermissionType.ACCESSIBILITY -> isAccessibilityEnabled(context)
            }
            !granted && entry.perm in setOf(
                "__accessibility__",
                Manifest.permission.SYSTEM_ALERT_WINDOW,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionHeader(
                title = "Permissions",
                subtitle = "$grantedCount/$total granted · " +
                    if (grantedCount == total) "all granted" else "missing ${total - grantedCount}",
            )
        }

        // ---- v2.0.5 Task 60: Critical-permission warning banner ----
        // Shows at the very top whenever any critical permission is missing,
        // on EVERY visit to Hub → Permissions (not just onboarding).
        if (missingCritical.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(custom.error.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                        .border(1.dp, custom.error.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .clickable { grantAll() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("⚠️", color = custom.error, fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Missing permissions — some features won't work. Tap Grant All to enable.",
                        color = custom.error,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // ---- "Grant All" button (only visible if something is missing) ----
        if (grantedCount < total) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(custom.accent.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
                        .border(1.dp, custom.accent.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                        .clickable { grantAll() }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Verified, contentDescription = null, tint = custom.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Grant all runtime permissions",
                        color = custom.accent,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "opens one dialog",
                        color = custom.fg3,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        // ---- Grouped permission rows ----
        PermissionGroup.entries.forEach { group ->
            val entriesInGroup = grouped[group] ?: return@forEach
            item {
                Text(
                    text = group.label.uppercase(),
                    color = custom.fg3,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
            }
            items(entriesInGroup, key = { it.perm }) { entry ->
                PermissionRow(
                    entry = entry,
                    granted = permissionStates[entry.perm] == true,
                    onGrant = { requestPermission(entry) },
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    entry: PermissionEntry,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.ink2, RoundedCornerShape(8.dp))
            .border(1.dp, custom.inkBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(
                    if (granted) custom.accent.copy(alpha = 0.15f) else custom.error.copy(alpha = 0.12f),
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (granted) Icons.Outlined.Verified else Icons.Outlined.Lock,
                contentDescription = null,
                tint = if (granted) custom.accent else custom.error,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.display,
                color = m3.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
            )
            Text(
                text = entry.perm.removePrefix("android.permission."),
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
        }
        if (granted) {
            Box(
                modifier = Modifier
                    .background(custom.accent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "GRANTED",
                    color = custom.accent,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else if (entry.type == PermissionType.INTERNET) {
            // INTERNET is auto-granted on install — should never reach here.
            Box(
                modifier = Modifier
                    .background(custom.accent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = "AUTO",
                    color = custom.accent,
                    fontFamily = MonoFontFamily,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            // Grant button — emerald pill, tappable.
            Box(
                modifier = Modifier
                    .background(custom.accent, RoundedCornerShape(6.dp))
                    .clickable(onClick = onGrant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "Grant",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
