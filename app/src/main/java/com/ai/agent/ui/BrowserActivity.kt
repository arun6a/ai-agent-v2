package com.ai.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
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

        // v2.2.5: Set up the layout FIRST (fast — no WebView yet) so onCreate
        // returns immediately. This prevents ANR ("Aira not responding") which
        // was caused by heavy WebView initialization blocking the main thread.
        // The ANR also disconnected the accessibility service (instance=null).
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // Top bar with mode indicator + switch to chat button
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0F0F12"))
            setPadding(16, 12, 16, 12)
            gravity = Gravity.CENTER_VERTICAL
        }

        modeTextView = TextView(this).apply {
            text = "🌐 AI Mode — loading..."
            setTextColor(Color.parseColor("#10B981"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val switchBtn = Button(this).apply {
            text = "← Chat"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.WHITE)
            textSize = 12f
            setOnClickListener { finish() }
        }

        topBar.addView(modeTextView)
        topBar.addView(switchBtn)
        root.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        // Loading placeholder (shown until WebView is ready)
        loadingView = TextView(this).apply {
            text = "Loading browser..."
            setTextColor(Color.parseColor("#A1A1AA"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 100, 0, 0)
        }
        root.addView(loadingView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        setContentView(root)

        // Store URL for deferred WebView init
        pendingUrl = intent?.getStringExtra(EXTRA_URL) ?: "https://www.google.com"
    }

    // v2.2.5: Create WebView in onResume (after layout is shown) to prevent ANR.
    // This defers the heavy WebView initialization until after the activity is visible.
    private var pendingUrl: String = "https://www.google.com"
    private lateinit var modeTextView: TextView
    private lateinit var loadingView: TextView
    private var webViewInitialized = false

    override fun onResume() {
        super.onResume()
        if (!webViewInitialized) {
            webViewInitialized = true
            // Post to next frame so onResume completes first (no ANR)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                initializeWebView()
            }, 100)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initializeWebView() {
        try {
            Log.i(TAG, "Initializing WebView (deferred from onResume)")

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
                settings.userAgentString = (
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/121.0.0.0 Mobile Safari/537.36"
                    )

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        Log.i(TAG, "Page loading: $url")
                        modeTextView.text = "🌐 AI Mode — loading $url"
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.i(TAG, "Page loaded: $url")
                        modeTextView.text = "🌐 AI Mode — browsing"
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

                loadUrl(pendingUrl)
            }

            webView = wv
            activeWebView = wv
            activeInstance = this

            // Replace loading view with WebView
            val root = findViewById<LinearLayout>(android.R.id.content)?.let { it.getChildAt(0) as? LinearLayout }
                ?: return
            // Remove loading view
            if (root.childCount > 1) root.removeViewAt(1)
            // Add WebView
            val webContainer = FrameLayout(this).apply {
                setBackgroundColor(Color.WHITE)
                addView(wv)
            }
            root.addView(webContainer, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))

            // v2.2.5: User takeover detection
            wv.setOnTouchListener { _, _ ->
                if (currentMode != "user") {
                    currentMode = "user"
                    modeTextView.text = "👤 User Mode — AI paused"
                    modeTextView.setTextColor(Color.parseColor("#F59E0B"))
                    Log.i(TAG, "User took over browser — AI paused")
                    userPaused = true
                }
                false
            }

            modeTextView.text = "🌐 AI Mode — browsing"

            // Load URL from intent if one was passed (already in pendingUrl)
            val url = intent?.getStringExtra(EXTRA_URL)
            if (!url.isNullOrBlank()) {
                val finalUrl = if (url.startsWith("http")) url else "https://$url"
                wv.loadUrl(finalUrl)
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebView initialization failed", e)
            modeTextView.text = "⚠️ Browser error: ${e.message}"
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

        // v2.2.1: Browser mode tracking — "ai" (AI controls) or "user" (user took over).
        @Volatile
        var currentMode: String = "ai"

        // v2.2.1: When true, AI should pause browser tools until user is done.
        @Volatile
        var userPaused: Boolean = false

        // v2.2.1: Called by AI to check if user has taken over the browser.
        fun isUserMode(): Boolean = currentMode == "user"

        // v2.2.1: Called by AI to resume control (after user is done).
        fun resumeAiMode() {
            currentMode = "ai"
            userPaused = false
        }

        /**
         * Launch the browser with a specific URL.
         * Uses SINGLE_TOP + REORDER_TO_FRONT so we reuse the existing instance.
         */
        fun launch(context: Context, url: String) {
            // v2.2.1: Reset to AI mode when launching a new browser session
            currentMode = "ai"
            userPaused = false
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
