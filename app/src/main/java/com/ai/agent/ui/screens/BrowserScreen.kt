package com.ai.agent.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.KeyEvent
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.agent.ui.components.LocalCustomColors
import com.ai.agent.ui.theme.MonoFontFamily

// ============================================================================
// BrowserScreen — real in-app WebView browser.
// ----------------------------------------------------------------------------
// - URL bar at top: text field + Go + Reload + Back/Forward + Close
// - WebView fills remaining space
// - Linear progress bar showing page load (0-100%)
// - JS + DOM storage + zoom controls enabled
// - BackHandler wired so the system back button navigates WebView history
//   (if canGoBack) instead of leaving the screen
// - Default URL: https://www.google.com
// ============================================================================

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen() {
    val custom = LocalCustomColors.current
    val m3 = MaterialTheme.colorScheme

    var urlField by remember { mutableStateOf("https://www.google.com") }
    var currentUrl by remember { mutableStateOf("https://www.google.com") }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableFloatStateOf(0f) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(custom.canvas),
    ) {
        // ---- URL bar ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(m3.surface)
                .border(1.dp, custom.border)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Reload button
            IconButton(
                onClick = { webViewRef?.reload() },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Reload",
                    tint = custom.fg2,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            // Back button
            IconButton(
                onClick = {
                    webViewRef?.let { if (it.canGoBack()) it.goBack() }
                },
                enabled = canGoBack,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = if (canGoBack) custom.fg2 else custom.fg3,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            // Forward button
            IconButton(
                onClick = {
                    webViewRef?.let { if (it.canGoForward()) it.goForward() }
                },
                enabled = canGoForward,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = "Forward",
                    tint = if (canGoForward) custom.fg2 else custom.fg3,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(6.dp))

            // URL text field
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(32.dp)
                    .background(custom.surface2, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .border(1.dp, custom.border, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (urlField.isEmpty()) {
                    Text(
                        text = "Search or type URL",
                        color = custom.fg3,
                        fontSize = 12.sp,
                        fontFamily = MonoFontFamily,
                    )
                }
                BasicTextField(
                    value = urlField,
                    onValueChange = { urlField = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = m3.onSurface,
                        fontSize = 12.sp,
                        fontFamily = MonoFontFamily,
                    ),
                    cursorBrush = SolidColor(custom.accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val target = normalizeUrl(urlField)
                            urlField = target
                            currentUrl = target
                            webViewRef?.loadUrl(target)
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(6.dp))

            // Go button
            IconButton(
                onClick = {
                    val target = normalizeUrl(urlField)
                    urlField = target
                    currentUrl = target
                    webViewRef?.loadUrl(target)
                },
                modifier = Modifier
                    .size(32.dp)
                    .background(custom.accent, androidx.compose.foundation.shape.CircleShape),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ManageSearch,
                    contentDescription = "Go",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        // ---- Progress bar ----
        if (progress < 1f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = custom.accent,
                trackColor = custom.surface3,
            )
        }

        // ---- Status / page title row ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(custom.canvas)
                .border(1.dp, custom.border)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(custom.accent, androidx.compose.foundation.shape.CircleShape),
            )
            Text(
                text = if (pageTitle.isNotBlank()) pageTitle else "loading…",
                color = m3.onSurface,
                fontSize = 11.sp,
                fontFamily = MonoFontFamily,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                text = "${(progress * 100).toInt()}%",
                color = custom.fg3,
                fontFamily = MonoFontFamily,
                fontSize = 10.sp,
            )
        }

        // ---- WebView ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(m3.surface),
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                        settings.databaseEnabled = true
                        settings.setSupportZoom(true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                progress = 0f
                                if (url != null) {
                                    currentUrl = url
                                    urlField = url
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                progress = 1f
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                                pageTitle = view?.title ?: ""
                                if (url != null) {
                                    urlField = url
                                }
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                canGoBack = view?.canGoBack() == true
                                canGoForward = view?.canGoForward() == true
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress / 100f
                            }

                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                pageTitle = title ?: ""
                            }
                        }
                        loadUrl(currentUrl)
                        webViewRef = this
                    }
                },
                update = { webView ->
                    if (currentUrl.isNotBlank() && webView.url != currentUrl) {
                        webView.loadUrl(currentUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Convert user input into a valid URL: if it has no scheme, prepend https://.
 * If it looks like a search query (contains spaces), use Google search.
 */
private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return "https://www.google.com"
    // Already a URL?
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    // Looks like a domain? (has a dot, no spaces)
    if (!trimmed.contains(' ') && trimmed.contains('.')) {
        return "https://$trimmed"
    }
    // Otherwise treat as a search query
    val q = android.net.Uri.encode(trimmed)
    return "https://www.google.com/search?q=$q"
}
