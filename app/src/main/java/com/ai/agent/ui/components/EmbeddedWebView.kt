package com.ai.agent.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.delay

// ============================================================================
// EmbeddedWebView — full-screen in-app browser overlay inside ChatScreen.
// ----------------------------------------------------------------------------
// v2.3.0: Replaces BrowserActivity for AI-initiated browser opens.
// NO Activity switch → MIUI never sees foreground change → a11y stays alive.
//
// v2.3.5: Fix "loading forever" issue:
//   - Real progress tracking via WebChromeClient.onProgressChanged (0-100%)
//   - LinearProgressIndicator at top shows actual page load %
//   - Timeout fallback: if page doesn't finish in 20s, hide loading anyway
//     (some sites hang on sub-resources forever — Google Analytics, ads, etc.)
//   - onReceivedError handler — show error message instead of hanging
//   - Page is shown AS SOON AS first paint happens (progress > 30%), not waiting
//     for 100% — many sites have lazy-loaded images that never finish.
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
    var isUserMode by remember { mutableStateOf(false) }
    // v2.3.5: Track actual progress (0-100)
    var progress by remember { mutableIntStateOf(0) }
    // isLoading = true until progress reaches 100 OR timeout fires
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(url) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // v2.3.5: Track start time for timeout
    val loadStartTime by remember(url) { mutableStateOf(System.currentTimeMillis()) }

    // v2.3.5: Timeout fallback — if page doesn't finish in 20s, hide loading indicator
    LaunchedEffect(url, visible) {
        if (!visible) return@LaunchedEffect
        isLoading = true
        progress = 0
        errorMessage = null
        // Wait 20 seconds max for page to finish
        var elapsed = 0
        while (elapsed < 20 && isLoading) {
            delay(500)
            elapsed += 500
        }
        if (isLoading) {
            android.util.Log.w("EmbeddedWebView", "Page load timeout (20s) — hiding loading indicator")
            isLoading = false
            // Still try to complete pageLoadDeferred so AI tools don't hang
            try {
                val field = BrowserController::class.java.getDeclaredField("pageLoadDeferred")
                field.isAccessible = true
                val deferred = field.get(BrowserController)
                if (deferred is kotlinx.coroutines.CompletableDeferred<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (deferred as kotlinx.coroutines.CompletableDeferred<Boolean>).complete(true)
                }
            } catch (_: Throwable) {}
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 150)),
        exit = fadeOut(animationSpec = tween(durationMillis = 150)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1A1A1F)),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                // === Top bar: mode indicator + hide button + progress ===
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
                        text = when {
                            isUserMode -> "👤 User Mode — AI paused"
                            errorMessage != null -> "⚠️ Error"
                            isLoading && progress > 0 -> "Loading $progress%"
                            isLoading -> "Loading…"
                            else -> "🌐 AI Mode — browsing"
                        },
                        color = when {
                            isUserMode -> Color(0xFFF59E0B)
                            errorMessage != null -> Color(0xFFEF4444)
                            else -> Color(0xFF10B981)
                        },
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

                // v2.3.5: Linear progress bar at top (real % from WebChromeClient)
                if (isLoading && progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF10B981),
                        trackColor = Color(0xFF2A2A2F),
                    )
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
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        android.util.Log.i("EmbeddedWebView", "Page loading: $url")
                                        isLoading = true
                                        progress = 0
                                        currentUrl = url ?: ""
                                        errorMessage = null
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        android.util.Log.i("EmbeddedWebView", "Page loaded: $url")
                                        isLoading = false
                                        progress = 100
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

                                    // v2.3.5: Handle errors — show message instead of hanging
                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?
                                    ) {
                                        // Only fail on main frame errors, not sub-resources
                                        if (request?.isForMainFrame == true) {
                                            android.util.Log.e("EmbeddedWebView", "Page error: ${error?.description}")
                                            errorMessage = error?.description?.toString() ?: "Failed to load page"
                                            isLoading = false
                                        }
                                    }

                                    override fun onReceivedSslError(
                                        view: WebView?,
                                        handler: android.webkit.SslErrorHandler?,
                                        error: android.net.http.SslError?
                                    ) {
                                        android.util.Log.w("EmbeddedWebView", "SSL error: ${error?.primaryError}")
                                        handler?.cancel()
                                        errorMessage = "SSL certificate error"
                                        isLoading = false
                                    }
                                }

                                // v2.3.5: WebChromeClient with progress tracking
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                        android.util.Log.d("EmbeddedWebView", "Progress: $newProgress%")
                                        // v2.3.5: If progress > 80%, consider it "mostly loaded" —
                                        // many sites have lazy-loaded images that never finish 100%
                                        if (newProgress >= 90 && isLoading) {
                                            isLoading = false
                                        }
                                    }
                                }

                                // Load the URL
                                val finalUrl = if (url.startsWith("http")) url else "https://$url"
                                loadUrl(finalUrl)

                                // User takeover detection
                                setOnTouchListener { _, _ ->
                                    if (!isUserMode) {
                                        isUserMode = true
                                        onUserTakeover()
                                        android.util.Log.i("EmbeddedWebView", "User took over — AI paused")
                                    }
                                    false
                                }

                                onWebViewReady(this)
                            }
                        },
                        update = { webView ->
                            val currentWvUrl = webView.url ?: ""
                            val targetUrl = if (url.startsWith("http")) url else "https://$url"
                            if (currentWvUrl != targetUrl && targetUrl.isNotEmpty()) {
                                isLoading = true
                                progress = 0
                                webView.loadUrl(targetUrl)
                            }
                        },
                    )

                    // v2.3.5: Loading overlay — show until progress > 30% (first paint)
                    if (isLoading && progress < 30) {
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

                    // v2.3.5: Error overlay — shown if page fails to load
                    if (errorMessage != null) {
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
                                Text(
                                    text = "⚠️",
                                    color = Color(0xFFEF4444),
                                    fontSize = 32.sp,
                                )
                                Text(
                                    text = "Failed to load page",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                )
                                Text(
                                    text = errorMessage ?: "",
                                    color = Color(0xFF888890),
                                    fontFamily = MonoFontFamily,
                                    fontSize = 11.sp,
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

    DisposableEffect(visible) {
        onDispose {
            if (!visible) {
                BrowserController.clearEmbeddedWebView()
                isUserMode = false
                isLoading = true
                progress = 0
                errorMessage = null
            }
        }
    }
}
