package com.ai.agent.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ai.agent.R
import com.ai.agent.llm.AIProvider
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily

// ============================================================================
// OnboardingScreen — 3-step first-run flow.
// ----------------------------------------------------------------------------
// Step 1: Welcome      — big emerald orb + tagline + "Get Started"
// Step 2: Permissions  — 4 critical permissions with Grant buttons + checks
// Step 3: Provider Setup — pick a provider, enter API key + model + test
//
// "Skip" buttons let the user continue regardless. "Finish" persists the
// provider config and navigates to ChatScreen.
// ============================================================================

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val context = LocalContext.current

    var step by rememberSaveable { mutableStateOf(0) }

    // Provider-setup state
    val topProviders = remember {
        AIProvider.PROVIDERS.filter { it.id in listOf("zai_direct", "groq", "gemini", "openrouter") }
    }
    var selectedProviderId by rememberSaveable {
        mutableStateOf(AIProvider.getProviderId(context).ifBlank { "zai_direct" })
    }
    var apiKey by rememberSaveable { mutableStateOf(AIProvider.getApiKey(context)) }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var selectedModel by rememberSaveable { mutableStateOf(AIProvider.getModel(context)) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(custom.canvas),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        ) {
            // Step indicator
            StepIndicator(current = step, total = 3)
            Spacer(Modifier.height(24.dp))

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    fadeIn(tween(300)) togetherWith fadeOut(tween(200))
                },
                label = "onboarding-step",
            ) { s ->
                when (s) {
                    0 -> WelcomeStep(onGetStarted = { step = 1 })
                    1 -> PermissionsStep(
                        onContinue = { step = 2 },
                        onSkip = { step = 2 },
                    )
                    2 -> ProviderSetupStep(
                        topProviders = topProviders,
                        selectedProviderId = selectedProviderId,
                        onSelectProvider = {
                            selectedProviderId = it
                            // Reset model when provider changes
                            val prov = topProviders.find { p -> p.id == it }
                            selectedModel = prov?.models?.firstOrNull()?.id ?: ""
                            testStatus = null
                        },
                        apiKey = apiKey,
                        onApiKeyChange = { apiKey = it; testStatus = null },
                        showApiKey = showApiKey,
                        onToggleShowApiKey = { showApiKey = !showApiKey },
                        selectedModel = selectedModel,
                        onSelectModel = { selectedModel = it },
                        needsApiKey = topProviders.find { it.id == selectedProviderId }?.needsApiKey ?: false,
                        models = topProviders.find { it.id == selectedProviderId }?.models ?: emptyList(),
                        testStatus = testStatus,
                        testing = testing,
                        onTest = {
                            testing = true
                            testStatus = null
                            // Persist current values before the test
                            AIProvider.setProvider(context, selectedProviderId)
                            if (apiKey.isNotBlank()) AIProvider.setApiKey(context, apiKey)
                            if (selectedModel.isNotBlank()) AIProvider.setModel(context, selectedModel)
                            val configured = AIProvider.isConfigured(context)
                            testStatus = if (configured) "✓ configured" else "missing API key"
                            testing = false
                        },
                        onSkip = {
                            // v3.2.6: Skip leaves the provider unconfigured.
                            // The chat will show a "Set up AI Settings first"
                            // error until the user adds an API key + model.
                            onFinished()
                        },
                        onFinish = {
                            // Persist the chosen provider + key + model
                            AIProvider.setProvider(context, selectedProviderId)
                            if (apiKey.isNotBlank()) AIProvider.setApiKey(context, apiKey)
                            if (selectedModel.isNotBlank()) AIProvider.setModel(context, selectedModel)
                            onFinished()
                        },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Step indicator — three dots with the current one highlighted emerald
// ---------------------------------------------------------------------------

@Composable
private fun StepIndicator(current: Int, total: Int) {
    val custom = LocalCustomColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (i in 0 until total) {
            val active = i == current
            Box(
                modifier = Modifier
                    .height(4.dp)
                    .width(if (active) 24.dp else 12.dp)
                    .background(
                        if (active) custom.accent else custom.surface3,
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Step 1: Welcome
// ---------------------------------------------------------------------------

@Composable
private fun WelcomeStep(onGetStarted: () -> Unit) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Aira logo (PNG resource).
        Image(
            painter = androidx.compose.ui.res.painterResource(R.drawable.aira_logo),
            contentDescription = "Aira logo",
            modifier = Modifier.size(140.dp),
        )

        Spacer(Modifier.height(24.dp))
        Text(
            text = "Welcome to Aira",
            color = m3.onSurface,
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Personal AI Assistant",
            color = custom.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Your intelligent companion for seamless device control and daily tasks.",
            color = custom.fg2,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(32.dp))
        PrimaryButton(
            text = "Get Started",
            icon = Icons.Outlined.ArrowForward,
            onClick = onGetStarted,
        )
    }
}

// ---------------------------------------------------------------------------
// Step 2: Permissions
// ---------------------------------------------------------------------------

private data class PermissionItem(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val grantAction: () -> Unit,
    val isGranted: () -> Boolean,
)

@Composable
private fun PermissionsStep(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val context = LocalContext.current

    // Mutable trigger to recompose when permission state changes
    var refresh by remember { mutableStateOf(0) }
    val refreshTick = { refresh++ }

    // Mic + storage runtime-permission launcher
    val runtimePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> refreshTick() }

    // Re-evaluate each permission
    fun micGranted() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun storageGranted(): Boolean {
        val readExt = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        // Android 13+ uses READ_MEDIA_IMAGES / etc. — treat any of them as "granted"
        val mediaImages = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_MEDIA_IMAGES,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return readExt || mediaImages
    }

    fun overlayGranted() = Settings.canDrawOverlays(context)

    // Approximate check for accessibility — we cannot directly query whether our
    // specific service is enabled (no system API for that), but we can scan the
    // enabled-services string.
    fun accessibilityGranted(): Boolean {
        val enabled = try {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: ""
        } catch (_: Exception) { "" }
        return enabled.contains(context.packageName, ignoreCase = true)
    }

    // v2.0.9 Task 92: Notification Listener access — required for
    // NotificationListenerService (reading + auto-replying to WhatsApp / SMS
    // notifications, mirroring v1 behavior).
    fun notificationListenerGranted(): Boolean = isNotificationListenerEnabled(context)

    val items = listOf(
        PermissionItem(
            title = "Accessibility Service",
            description = "Required to control your phone (tap, type, read screen).",
            icon = Icons.Outlined.Verified,
            grantAction = {
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) { }
                refreshTick()
            },
            isGranted = ::accessibilityGranted,
        ),
        PermissionItem(
            title = "Overlay (Display over apps)",
            description = "Floating button + status indicator.",
            icon = Icons.Outlined.Lock,
            grantAction = {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
                    context.startActivity(intent)
                } catch (_: Exception) { }
                refreshTick()
            },
            isGranted = ::overlayGranted,
        ),
        PermissionItem(
            title = "Microphone",
            description = "Voice input.",
            icon = Icons.Outlined.Mic,
            grantAction = {
                runtimePermLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            },
            isGranted = ::micGranted,
        ),
        PermissionItem(
            title = "Storage / Media",
            description = "Attachments + knowledge base.",
            icon = Icons.Outlined.Lock,
            grantAction = {
                val perms = mutableListOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                )
                // Android 13+ uses scoped media permissions
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    perms.add(Manifest.permission.READ_MEDIA_IMAGES)
                    perms.add(Manifest.permission.READ_MEDIA_AUDIO)
                    perms.add(Manifest.permission.READ_MEDIA_VIDEO)
                }
                runtimePermLauncher.launch(perms.toTypedArray())
            },
            isGranted = ::storageGranted,
        ),
        // v2.0.9 Task 92: Notification Access row — required for the
        // NotificationListenerService (read + auto-reply to WhatsApp / SMS,
        // mirror of v1 behavior).
        PermissionItem(
            title = "Notification Access",
            description = "Required to read + reply to notifications (WhatsApp, SMS, etc.)",
            icon = Icons.Outlined.Notifications,
            grantAction = {
                try {
                    val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                } catch (_: Exception) { }
                refreshTick()
            },
            isGranted = ::notificationListenerGranted,
        ),
    )

    // Force re-evaluation on every recomposition triggered by `refresh`
    LaunchedEffect(refresh) { /* just for the key */ }

    val grantedCount = items.count { it.isGranted() }
    val allGranted = grantedCount == items.size

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Grant Permissions",
            color = m3.onSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "These unlock the agent's superpowers. You can grant them later from Settings.",
            color = custom.fg2,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { item ->
                PermissionRow(item = item, granted = item.isGranted())
            }
        }
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                PrimaryButton(
                    text = if (allGranted) "Continue" else "Continue anyway",
                    icon = Icons.Outlined.ArrowForward,
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SecondaryButton(text = "Skip", onClick = onSkip)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "$grantedCount/${items.size} granted",
            color = if (allGranted) custom.accent else custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun PermissionRow(item: PermissionItem, granted: Boolean) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (granted) custom.accent.copy(alpha = 0.08f) else custom.surface2,
                RoundedCornerShape(10.dp),
            )
            .border(
                1.dp,
                if (granted) custom.accent.copy(alpha = 0.4f) else custom.border,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    if (granted) custom.accent.copy(alpha = 0.15f) else custom.surface3,
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (granted) Icons.Filled.Check else item.icon,
                contentDescription = null,
                tint = if (granted) custom.accent else custom.fg3,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = m3.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.description,
                color = custom.fg2,
                fontSize = 11.sp,
                lineHeight = 14.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .background(
                    if (granted) custom.accent.copy(alpha = 0.15f) else custom.accent.copy(alpha = 0.12f),
                    RoundedCornerShape(8.dp),
                )
                .clickable(onClick = item.grantAction)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (granted) "DONE" else "GRANT",
                color = if (granted) custom.accent else custom.accent,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Step 3: Provider setup
// ---------------------------------------------------------------------------

@Composable
private fun ProviderSetupStep(
    topProviders: List<AIProvider.ProviderInfo>,
    selectedProviderId: String,
    onSelectProvider: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    showApiKey: Boolean,
    onToggleShowApiKey: () -> Unit,
    selectedModel: String,
    onSelectModel: (String) -> Unit,
    needsApiKey: Boolean,
    models: List<AIProvider.ModelInfo>,
    testStatus: String?,
    testing: Boolean,
    onTest: () -> Unit,
    onSkip: () -> Unit,
    onFinish: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Choose your AI",
            color = m3.onSurface,
            fontSize = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Pick a provider and add your API key. You can change this any time in Hub → LLM → Configure.",
            color = custom.fg2,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            topProviders.forEach { provider ->
                ProviderPickCard(
                    provider = provider,
                    selected = provider.id == selectedProviderId,
                    onClick = { onSelectProvider(provider.id) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        if (needsApiKey) {
            Text(
                text = "API KEY",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            ApiKeyField(
                value = apiKey,
                onChange = onApiKeyChange,
                show = showApiKey,
                onToggle = onToggleShowApiKey,
            )
            Spacer(Modifier.height(12.dp))
        } else {
            Text(
                text = "This provider doesn't need an API key.",
                color = custom.accent,
                fontFamily = MonoFontFamily,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(12.dp))
        }
        if (models.isNotEmpty()) {
            Text(
                text = "MODEL",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            // Chips row
            androidx.compose.foundation.lazy.LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(models) { model ->
                    val active = model.id == selectedModel
                    Box(
                        modifier = Modifier
                            .background(
                                if (active) custom.accent.copy(alpha = 0.15f) else custom.surface2,
                                RoundedCornerShape(14.dp),
                            )
                            .border(
                                1.dp,
                                if (active) custom.accent.copy(alpha = 0.5f) else custom.border,
                                RoundedCornerShape(14.dp),
                            )
                            .clickable { onSelectModel(model.id) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = model.name,
                            color = if (active) custom.accent else custom.fg2,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        // Test button + status
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton(
                text = if (testing) "Testing…" else "Test connection",
                onClick = onTest,
                enabled = !testing,
            )
            if (testStatus != null) {
                Text(
                    text = testStatus,
                    color = if (testStatus.startsWith("✓")) custom.accent else custom.error,
                    fontFamily = MonoFontFamily,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                PrimaryButton(
                    text = "Finish",
                    icon = Icons.Filled.Check,
                    onClick = onFinish,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SecondaryButton(text = "Skip for now", onClick = onSkip)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Skip uses the Z.ai Sandbox proxy (no key needed, sandbox must be running).",
            color = custom.fg3,
            fontSize = 10.sp,
            lineHeight = 13.sp,
        )
    }
}

@Composable
private fun ProviderPickCard(
    provider: AIProvider.ProviderInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (selected) custom.accent.copy(alpha = 0.08f) else custom.surface2,
                RoundedCornerShape(10.dp),
            )
            .border(
                1.dp,
                if (selected) custom.accent.copy(alpha = 0.5f) else custom.border,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    if (selected) custom.accent else custom.surface3,
                    RoundedCornerShape(8.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = null,
                tint = if (selected) Color.White else custom.fg3,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = provider.name,
                    color = m3.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(custom.accent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = "SELECTED",
                            color = custom.accent,
                            fontFamily = MonoFontFamily,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = provider.description.take(100) + if (provider.description.length > 100) "…" else "",
                color = custom.fg2,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ApiKeyField(
    value: String,
    onChange: (String) -> Unit,
    show: Boolean,
    onToggle: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .background(custom.surface2, RoundedCornerShape(10.dp))
            .border(1.dp, custom.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(text = "sk-…", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 12.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = m3.onSurface,
                    fontSize = 12.sp,
                    fontFamily = MonoFontFamily,
                ),
                cursorBrush = SolidColor(custom.accent),
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        IconButton(onClick = onToggle, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                contentDescription = if (show) "Hide key" else "Show key",
                tint = custom.fg3,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared buttons
// ---------------------------------------------------------------------------

@Composable
private fun PrimaryButton(
    text: String,
    icon: ImageVector? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val custom = LocalCustomColors.current
    Row(
        modifier = modifier
            .background(
                if (enabled) custom.accent else custom.surface3,
                RoundedCornerShape(10.dp),
            )
            .shadow(if (enabled) 6.dp else 0.dp, RoundedCornerShape(10.dp), clip = false)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) Color.White else custom.fg3,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (icon != null) {
            Spacer(Modifier.width(6.dp))
            Icon(imageVector = icon, contentDescription = null, tint = if (enabled) Color.White else custom.fg3, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val custom = LocalCustomColors.current
    Row(
        modifier = Modifier
            .background(custom.surface2, RoundedCornerShape(10.dp))
            .border(1.dp, custom.border, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = custom.fg2,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// ---------------------------------------------------------------------------
// v2.0.9 Task 92: Notification-listener-enabled helper.
// Mirrors v1 — reads the system "enabled_notification_listeners" Secure
// setting and checks whether our package appears in the flat ComponentName
// list. The OS sets this when the user toggles our NotificationListener
// service in the system "Notification access" screen.
// ---------------------------------------------------------------------------

private fun isNotificationListenerEnabled(context: android.content.Context): Boolean {
    val flat = try {
        android.provider.Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        )
    } catch (_: Exception) { null } ?: return false
    return flat.contains(context.packageName)
}
