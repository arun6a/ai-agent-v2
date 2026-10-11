package com.ai.agent.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

// ============================================================================
// BrowserActivity — visible in-app WebView host.
// ----------------------------------------------------------------------------
// Carried-over host for the visible browser used by BrowserController. The
// full v1 implementation (with mode toggle, AI status bar, URL bar, login
// detection) will be re-wired here once the v2 BrowserScreen is upgraded
// from its current placeholder. For now this provides the minimal surface
// area that BrowserController needs to compile + run:
//   - companion.activeWebView  (read by BrowserController.getWebView)
//   - companion.launch(ctx, url)  (called by BrowserController.openUrl)
// ============================================================================

class BrowserActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "BrowserActivity created (minimal host)")
        activeInstance = this
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
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

        /** Reference to the active WebView — used by BrowserController. Null until a real WebView is wired in. */
        @Volatile
        var activeWebView: WebView? = null
            private set

        /** Reference to the activity — used by BrowserController to run JS on the UI thread. */
        @Volatile
        var activeInstance: BrowserActivity? = null
            private set
    }
}
