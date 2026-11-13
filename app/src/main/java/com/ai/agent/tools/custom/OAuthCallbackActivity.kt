package com.ai.agent.tools.custom

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * v4.0.88: OAuthCallbackActivity — catches the aira://callback deep link
 * that OAuth providers redirect to after the user authorizes.
 *
 * The activity is launched via intent-filter (AndroidManifest) when the browser
 * redirects to aira://callback?code=xxx&state=yyy. It passes the params to
 * ConnectorExecutor.handleCallback() which completes the token exchange.
 *
 * This activity is transparent — it finishes immediately after handling.
 */
class OAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        Log.i(TAG, "OAuth callback received: $data")

        val code = data.getQueryParameter("code")
        val state = data.getQueryParameter("state")
        val error = data.getQueryParameter("error")

        ConnectorExecutor.handleCallback(applicationContext, code, state, error)
    }

    companion object {
        private const val TAG = "OAuthCallback"
    }
}
