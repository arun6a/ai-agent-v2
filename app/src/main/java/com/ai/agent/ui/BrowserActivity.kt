package com.ai.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

// ============================================================================
// BrowserActivity — visible in-app WebView host.
// ----------------------------------------------------------------------------
// A real WebView is created in onCreate() and set as the activity's content
// view so the screen is never blank. The WebView is also exposed to
// BrowserController via `activeWebView` so the agent loop can call
// evaluateJavascript / readStructured / etc. against the page the user is
// actually looking at.
//
// Lifecycle:
//   - onCreate:  build WebView, configure JS/DOM storage/zoom, set client,
//                load the URL from the launching Intent (or google.com).
//   - onNewIntent: when launched with SINGLE_TOP + a new URL, load it
//                immediately without recreating the activity.
//   - onDestroy:  clear the static references if the activity is finishing.
// ============================================================================

class BrowserActivity : AppCompatActivity() {

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "BrowserActivity created (real WebView host)")

        // ===== Build a real WebView so the screen is never blank =====
        val wv = WebView(this).apply {
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
            // Identify as a mobile Chrome so most sites serve the mobile UI.
            settings.userAgentString = (
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/121.0.0.0 Mobile Safari/537.36"
                )

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    Log.i(TAG, "Page loading: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(TAG, "Page loaded: $url")
                    // Notify any pending page-load waiters in BrowserController.
                    try {
                        val field = com.ai.agent.browser.BrowserController::class.java
                            .getDeclaredField("pageLoadDeferred")
                        field.isAccessible = true
                        val deferred = field.get(com.ai.agent.browser.BrowserController)
                        if (deferred is kotlinx.coroutines.CompletableDeferred<*>) {
                            @Suppress("UNCHECKED_CAST")
                            (deferred as kotlinx.coroutines.CompletableDeferred<Boolean>).complete(true)
                        }
                    } catch (_: Throwable) {
                        // Reflection is best-effort — if the field name changes,
                        // the page-load wait just times out (15s) and we continue.
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {}

            // Default URL — overridden below if the launching intent has one.
            loadUrl("https://www.google.com")
        }

        webView = wv
        activeWebView = wv
        activeInstance = this

        // Use a FrameLayout so we can add the WebView without XML — works on
        // all API levels and avoids the previous "no content view" crash.
        val root = FrameLayout(this).apply { addView(wv) }
        setContentView(root)

        // Load URL from intent if one was passed.
        val url = intent?.getStringExtra(EXTRA_URL)
        if (!url.isNullOrBlank()) {
            val finalUrl = if (url.startsWith("http")) url else "https://$url"
            wv.loadUrl(finalUrl)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val finalUrl = if (url.startsWith("http")) url else "https://$url"
        Log.i(TAG, "onNewIntent — loading URL: $finalUrl")
        webView?.loadUrl(finalUrl)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            webView?.let {
                it.stopLoading()
                it.removeAllViews()
                (it.parent as? android.view.ViewGroup)?.removeView(it)
                it.destroy()
            }
            webView = null
            activeWebView = null
            activeInstance = null
            Log.i(TAG, "BrowserActivity finishing — WebView references cleared")
        }
    }

    companion object {
        private const val TAG = "BrowserActivity"
        const val EXTRA_URL = "url"

        /**
         * Launch the browser with a specific URL.
         * Uses SINGLE_TOP + REORDER_TO_FRONT so we reuse the existing instance.
         */
        fun launch(context: Context, url: String) {
            val intent = Intent(context, BrowserActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
            context.startActivity(intent)
        }

        /** Reference to the active WebView — used by BrowserController. */
        @Volatile
        var activeWebView: WebView? = null
            private set

        /** Reference to the activity — used by BrowserController to run JS on the UI thread. */
        @Volatile
        var activeInstance: BrowserActivity? = null
            private set
    }
}
