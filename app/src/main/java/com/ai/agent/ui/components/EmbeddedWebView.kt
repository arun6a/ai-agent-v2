package com.ai.agent.ui.components

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.agent.browser.BrowserController
import com.ai.agent.ui.theme.MonoFontFamily

// ============================================================================
// EmbeddedWebView — full-screen in-app browser overlay inside ChatScreen.
// ----------------------------------------------------------------------------
// v2.3.0: Replaces BrowserActivity for AI-initiated browser opens.
// NO Activity switch → MIUI never sees foreground change → a11y stays alive.
//
// v2.3.5: Fix "blank screen" issue:
//   - Removed slideInVertically animation (was delaying WebView creation by ~300ms)
//   - Added loading indicator overlay with URL shown while page loads
//   - Track isLoading state via onPageStarted/onPageFinished
//   - Background is dark during loading (not white) so user knows something is happening
//
// Flow:
// - AI calls browserOpen() → ChatViewModel sets webViewVisible=true + url
// - This overlay appears on top of chat (full screen) — INSTANTLY (no slide animation)
// - Loading indicator shows "Loading <url>..." immediately
// - WebView created via AndroidView, loads URL, onPageFinished hides loading indicator
// - BrowserController gets reference for AI tools (browserClickText, etc.)
// - User sees page live → can tap to switch to User Mode (AI pauses)
// - User taps "← Chat" → overlay hides → WebView destroyed (saves RAM)
// ============================================================================

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedWebView(
    url: String,
    visible: Boolean,
    onHide: () -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onUserTakeover: () -> Unit,
) {
    // Track mode for display
    var isUserMode by remember { mutableStateOf(false) }
    // v2.3.5: Track loading state — show indicator until page finishes
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(url) }

    AnimatedVisibility(
        visible = visible,
        // v2.3.5: Only fade in (no slide) — slide delays WebView creation
        enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(durationMillis = 150)),
        exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(durationMillis = 150)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A1F)),  // dark during loading (not white)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                // === Top bar: mode indicator + hide button + loading spinner ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F0F12))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Color(0xFF10B981),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        text = if (isUserMode) "👤 User Mode — AI paused"
                               else if (isLoading) "Loading…"
                               else "🌐 AI Mode — browsing",
                        color = if (isUserMode) Color(0xFFF59E0B) else Color(0xFF10B981),
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = onHide,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF10B981),
                        ),
                    ) {
                        Text("← Chat", color = Color.White, fontSize = 12.sp)
                    }
                }

                // === WebView (fills remaining space) ===
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.databaseEnabled = true
                                settings.javaScriptCanOpenWindowsAutomatically = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
                                settings.setSupportZoom(true)
                                settings.userAgentString = (
                                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                    "Chrome/121.0.0.0 Mobile Safari/537.36"
                                )

                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        android.util.Log.i("EmbeddedWebView", "Page loading: $url")
                                        isLoading = true
                                        currentUrl = url ?: ""
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        android.util.Log.i("EmbeddedWebView", "Page loaded: $url")
                                        isLoading = false
                                        // Notify BrowserController that page loaded
                                        try {
                                            val field = BrowserController::class.java
                                                .getDeclaredField("pageLoadDeferred")
                                            field.isAccessible = true
                                            val deferred = field.get(BrowserController)
                                            if (deferred is kotlinx.coroutines.CompletableDeferred<*>) {
                                                @Suppress("UNCHECKED_CAST")
                                                (deferred as kotlinx.coroutines.CompletableDeferred<Boolean>).complete(true)
                                            }
                                        } catch (_: Throwable) {}
                                    }

                                    // v2.3.5: Handle SSL errors gracefully (don't block UI)
                                    override fun onReceivedSslError(
                                        view: WebView?,
                                        handler: android.webkit.SslErrorHandler?,
                                        error: android.net.http.SslError?
                                    ) {
                                        android.util.Log.w("EmbeddedWebView", "SSL error: ${error?.primaryError}")
                                        handler?.cancel()
                                        isLoading = false
                                    }
                                }

                                webChromeClient = WebChromeClient()

                                // Load the URL
                                val finalUrl = if (url.startsWith("http")) url else "https://$url"
                                loadUrl(finalUrl)

                                // User takeover detection — when user touches the WebView
                                setOnTouchListener { _, _ ->
                                    if (!isUserMode) {
                                        isUserMode = true
                                        onUserTakeover()
                                        android.util.Log.i("EmbeddedWebView", "User took over — AI paused")
                                    }
                                    false  // don't consume — let WebView handle the touch
                                }

                                // Register with BrowserController so AI tools can use it
                                onWebViewReady(this)
                            }
                        },
                        update = { webView ->
                            // Update URL if changed while visible
                            val currentWvUrl = webView.url ?: ""
                            val targetUrl = if (url.startsWith("http")) url else "https://$url"
                            if (currentWvUrl != targetUrl && targetUrl.isNotEmpty()) {
                                isLoading = true
                                webView.loadUrl(targetUrl)
                            }
                        },
                    )

                    // v2.3.5: Loading overlay — shows URL + spinner while page loads
                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF1A1A1F)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFF10B981),
                                    strokeWidth = 3.dp,
                                )
                                Text(
                                    text = "Loading",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    text = currentUrl.ifEmpty { url },
                                    color = Color(0xFF888890),
                                    fontFamily = MonoFontFamily,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Cleanup: when overlay hides, destroy WebView to save RAM
    DisposableEffect(visible) {
        onDispose {
            if (!visible) {
                BrowserController.clearEmbeddedWebView()
                isUserMode = false
                isLoading = true
            }
        }
    }
}
