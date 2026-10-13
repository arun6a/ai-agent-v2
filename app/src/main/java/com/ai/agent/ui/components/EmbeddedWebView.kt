package com.ai.agent.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.agent.browser.BrowserController
import com.ai.agent.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay

// ============================================================================
// EmbeddedWebView — full-screen in-app browser overlay inside ChatScreen.
// ----------------------------------------------------------------------------
// v2.3.6: Fix "loading forever" + "blank screen after loading" issues:
//
// ROOT CAUSE of blank screen:
//   1. AnimatedVisibility had transition issues — overlay could disappear
//      during state changes (isLoading toggling, progress updates, etc.)
//   2. AndroidView `update` callback ran on EVERY recomposition. When
//      webView.url was null (during page load start), it triggered
//      webView.loadUrl() AGAIN → infinite reload loop → page never renders.
//
// FIX:
//   - Replace AnimatedVisibility with simple `if (visible)` — no transition
//     state, no exit animation, no chance of overlay disappearing
//   - Track last-loaded URL in remember() — only reload when URL ACTUALLY changes
//   - Real progress tracking via WebChromeClient.onProgressChanged
//   - Timeout fallback: hide loading at 20s even if page isn't done
//   - Show page at 30% progress (first paint)
// ============================================================================

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EmbeddedWebView(
    url: String,
    visible: Boolean,
    onHide: () -> Unit,
    onClose: () -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onUserTakeover: () -> Unit,
) {
    // v2.3.9: Don't early-return when !visible — that would remove from composition tree
    // and destroy the WebView. Instead, control visibility with Modifier (keeps WebView alive).
    // We DO early-return if there's no URL (first render before any browserOpen call).
    if (url.isEmpty()) return

    var isUserMode by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }
    var currentUrl by remember { mutableStateOf(url) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // v2.3.6: Track last loaded URL to prevent reload loops
    var lastLoadedUrl by remember { mutableStateOf("") }

    // v2.3.6: Timeout fallback — hide loading indicator after 20s
    LaunchedEffect(url) {
        isLoading = true
        progress = 0
        errorMessage = null
        var elapsed = 0
        while (elapsed < 20000 && isLoading) {
            delay(500)
            elapsed += 500
        }
        if (isLoading) {
            android.util.Log.w("EmbeddedWebView", "Page load timeout (20s) — hiding loading indicator")
            isLoading = false
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

    // v2.3.9: Control visibility via Modifier — keeps WebView alive when hidden (page state preserved).
    // When not visible, render with zero size + alpha 0 so it's not on screen but stays in composition.
    val visibilityModifier = if (visible) {
        Modifier.fillMaxSize()
    } else {
        Modifier.size(0.dp).alpha(0f)
    }

    Box(
        modifier = visibilityModifier
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
                // v2.3.9: "← Chat" = minimize (keep WebView alive, page state preserved)
                Button(
                    onClick = onHide,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981),
                    ),
                ) {
                    Text("← Chat", color = Color.White, fontSize = 12.sp)
                }
                Spacer(Modifier.size(6.dp))
                // v2.3.9: "✕" = close (destroy WebView, free RAM, page state lost)
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                    ),
                ) {
                    Text("✕", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // v2.3.6: Linear progress bar (real % from WebChromeClient)
            if (isLoading && progress in 1..99) {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF10B981),
                    trackColor = Color(0xFF2A2A2F),
                )
            }

            // === WebView + overlays ===
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

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
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

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress
                                    android.util.Log.d("EmbeddedWebView", "Progress: $newProgress%")
                                    if (newProgress >= 90 && isLoading) {
                                        isLoading = false
                                    }
                                }
                            }

                            // Load the URL
                            val finalUrl = if (url.startsWith("http")) url else "https://$url"
                            lastLoadedUrl = finalUrl
                            loadUrl(finalUrl)

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
                    // v2.3.6: ONLY reload when URL actually changes — NOT on every recomposition
                    // This was the root cause of the reload loop + blank screen
                    update = { webView ->
                        val targetUrl = if (url.startsWith("http")) url else "https://$url"
                        // Only reload if the target URL is different from what we last loaded
                        if (targetUrl != lastLoadedUrl && targetUrl.isNotEmpty()) {
                            android.util.Log.i("EmbeddedWebView", "URL changed: $lastLoadedUrl → $targetUrl, reloading")
                            lastLoadedUrl = targetUrl
                            isLoading = true
                            progress = 0
                            webView.loadUrl(targetUrl)
                        }
                    },
                )

                // Loading overlay — show until progress >= 30%
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

                // Error overlay
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
                        }
                    }
                }
            }
        }
    }

    // Cleanup when overlay is hidden
    DisposableEffect(visible) {
        onDispose {
            BrowserController.clearEmbeddedWebView()
            isUserMode = false
            isLoading = true
            progress = 0
            errorMessage = null
            lastLoadedUrl = ""
        }
    }
}
