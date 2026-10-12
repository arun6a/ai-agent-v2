package com.ai.agent.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.agent.llm.AIProvider
import com.ai.agent.llm.LLMClient
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope

// ============================================================================
// ProviderSettingsScreen — full-screen provider management.
// ----------------------------------------------------------------------------
// - Top bar with back button + "AI Provider" title
// - Vertical list of 13 providers (radio-style selection; active = emerald + ✓)
// - Below: API key field (password-style with show/hide toggle)
// - Endpoint field (only editable if provider.id == "custom")
// - Model dropdown (chips — list all models for that provider)
// - "Save" button — calls AIProvider.setApiKey/setModel/setEndpoint
// - "Test" button — makes a real LLM call ("Reply with 'ok'") and shows result
// - Status footer: current config summary
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(
    onBack: () -> Unit,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ----- mutable config state (synced from prefs on first launch) -----
    var providerId by rememberSaveable { mutableStateOf(AIProvider.getProviderId(context)) }
    var apiKey by rememberSaveable { mutableStateOf(AIProvider.getApiKey(context)) }
    var endpoint by rememberSaveable { mutableStateOf(AIProvider.getEndpoint(context)) }
    var modelId by rememberSaveable { mutableStateOf(AIProvider.getModel(context)) }
    var showApiKey by rememberSaveable { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    val providers = remember { AIProvider.PROVIDERS }
    val currentProvider = providers.find { it.id == providerId } ?: providers.first()
    val models = currentProvider.models

    fun switchProvider(newId: String) {
        providerId = newId
        val newProv = providers.find { it.id == newId } ?: return
        // Pick first model of new provider by default
        modelId = newProv.models.firstOrNull()?.id ?: ""
        // Endpoint follows the provider's default — unless it's "custom"
        endpoint = newProv.defaultEndpoint
        testResult = null
    }

    fun save() {
        AIProvider.setProvider(context, providerId)
        AIProvider.setApiKey(context, apiKey.trim())
        AIProvider.setModel(context, modelId)
        if (providerId == "custom") {
            AIProvider.setEndpoint(context, endpoint.trim())
        }
        toast = "Saved · ${currentProvider.name} · $modelId"
    }

    fun test() {
        if (testing) return
        // Persist before testing so the LLMClient picks up the latest config.
        save()
        testing = true
        testResult = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = LLMClient(context)
                    val resp = client.chat("Reply with just the word: ok", "You are a test echo.")
                    if (resp.reply.isNotBlank() && !resp.reply.contains("No API key", ignoreCase = true)) {
                        "✓ ok · ${resp.reply.take(40)}"
                    } else {
                        "✗ ${resp.reply.take(80)}"
                    }
                } catch (e: Exception) {
                    "✗ ${e.message?.take(80) ?: e::class.simpleName}"
                }
            }
            testResult = result
            testing = false
        }
    }

    Scaffold(
        containerColor = custom.canvas,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "AI Provider",
                        color = m3.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                            tint = m3.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = m3.surface,
                    titleContentColor = m3.onSurface,
                ),
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ===== Providers list =====
            item {
                SectionLabel("PROVIDERS · ${providers.size}")
                Spacer(Modifier.height(6.dp))
            }
            items(providers) { provider ->
                ProviderRadioRow(
                    provider = provider,
                    selected = provider.id == providerId,
                    onClick = { switchProvider(provider.id) },
                )
            }

            // ===== Config fields for the active provider =====
            item {
                Spacer(Modifier.height(8.dp))
                SectionLabel("CONFIGURATION · ${currentProvider.name}")
                Spacer(Modifier.height(8.dp))
            }

            // API Key (only if provider needs one)
            if (currentProvider.needsApiKey) {
                item {
                    FieldLabel("API KEY")
                    Spacer(Modifier.height(6.dp))
                    ApiKeyFieldRow(
                        value = apiKey,
                        onChange = { apiKey = it; testResult = null },
                        show = showApiKey,
                        onToggle = { showApiKey = !showApiKey },
                    )
                }
            } else {
                item {
                    InfoNote(
                        text = "This provider doesn't need an API key.",
                        accent = true,
                    )
                }
            }

            // Endpoint (only editable for custom)
            if (providerId == "custom") {
                item {
                    Spacer(Modifier.height(10.dp))
                    FieldLabel("ENDPOINT")
                    Spacer(Modifier.height(6.dp))
                    EndpointFieldRow(
                        value = endpoint,
                        onChange = { endpoint = it; testResult = null },
                    )
                }
            }

            // Models (chips)
            if (models.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(10.dp))
                    FieldLabel("MODEL · ${models.size} available")
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(models) { m ->
                            ModelChip(
                                name = m.name,
                                active = m.id == modelId,
                                onClick = { modelId = m.id; testResult = null },
                            )
                        }
                    }
                }
            }

            // Save + Test buttons
            item {
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(custom.accent, RoundedCornerShape(10.dp))
                            .clickable(onClick = { save() })
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = "Save",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(custom.surface2, RoundedCornerShape(10.dp))
                            .border(1.dp, custom.border, RoundedCornerShape(10.dp))
                            .clickable(enabled = !testing, onClick = { test() })
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = if (testing) "Testing…" else "Test connection",
                            color = custom.fg2,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    if (testResult != null) {
                        Text(
                            text = testResult!!,
                            color = if (testResult!!.startsWith("✓")) custom.accent else custom.error,
                            fontFamily = MonoFontFamily,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Status footer
            item {
                Spacer(Modifier.height(20.dp))
                ConfigSummary(
                    providerName = currentProvider.name,
                    modelName = modelId,
                    hasKey = apiKey.isNotBlank() || !currentProvider.needsApiKey,
                    endpoint = endpoint,
                )
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

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    val custom = LocalCustomColors.current
    Text(
        text = text,
        color = custom.fg3,
        fontFamily = MonoFontFamily,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun FieldLabel(text: String) {
    val custom = LocalCustomColors.current
    Text(
        text = text,
        color = custom.fg3,
        fontFamily = MonoFontFamily,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ProviderRadioRow(
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
                if (selected) custom.accent.copy(alpha = 0.08f) else m3.surface,
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
        // Radio indicator
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(
                    1.5.dp,
                    if (selected) custom.accent else custom.fg3,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(custom.accent, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = provider.name,
                    color = m3.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (provider.needsApiKey) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = "needs API key",
                        tint = custom.fg3,
                        modifier = Modifier.size(11.dp),
                    )
                }
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "active",
                        tint = custom.accent,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = provider.description.take(90) + if (provider.description.length > 90) "…" else "",
                color = custom.fg2,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun ApiKeyFieldRow(
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
                textStyle = TextStyle(
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

@Composable
private fun EndpointFieldRow(
    value: String,
    onChange: (String) -> Unit,
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
                Text(text = "https://…", color = custom.fg3, fontFamily = MonoFontFamily, fontSize = 12.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = m3.onSurface,
                    fontSize = 12.sp,
                    fontFamily = MonoFontFamily,
                ),
                cursorBrush = SolidColor(custom.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ModelChip(name: String, active: Boolean, onClick: () -> Unit) {
    val custom = LocalCustomColors.current
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
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = name,
            color = if (active) custom.accent else custom.fg2,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun InfoNote(text: String, accent: Boolean = false) {
    val custom = LocalCustomColors.current
    Text(
        text = text,
        color = if (accent) custom.accent else custom.fg2,
        fontFamily = MonoFontFamily,
        fontSize = 11.sp,
    )
}

@Composable
private fun ConfigSummary(
    providerName: String,
    modelName: String,
    hasKey: Boolean,
    endpoint: String,
) {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(custom.surface2, RoundedCornerShape(10.dp))
            .border(1.dp, custom.border, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "CURRENT CONFIG",
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
        SummaryRow(label = "provider", value = providerName)
        SummaryRow(label = "model", value = modelName)
        SummaryRow(label = "api key", value = if (hasKey) "✓ set" else "✗ missing")
        if (endpoint.isNotBlank()) {
            SummaryRow(label = "endpoint", value = endpoint.take(48) + if (endpoint.length > 48) "…" else "")
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    val custom = LocalCustomColors.current
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = custom.fg3,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            color = custom.fg2,
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
        )
    }
}
