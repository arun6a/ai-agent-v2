package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.ai.agent.service.AgentService
import com.ai.agent.storage.AgentDatabase

/**
 * Receives WiFi state change broadcasts to fire rules with:
 * - triggerType="wifi_connected" — fires when WiFi connects to a network
 * - triggerType="wifi_disconnected" — fires when WiFi disconnects
 *
 * The triggerValue can be:
 * - "*" or "any" — match any WiFi network
 * - "MyHomeNetwork" — match a specific SSID (requires location permission on Android 10+)
 *
 * For SSID matching on Android 10+, requires ACCESS_FINE_LOCATION (already declared).
 *
 * Because ConnectivityManager.NetworkCallback can only be registered programmatically,
 * we register it from AgentService. We also handle the legacy WifiManager broadcasts
 * for older Android versions.
 */
class WiFiReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WiFiReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "WiFi event: $action")

        val db = AgentDatabase(context)
        val rules = db.getRules(enabledOnly = true)

        when (action) {
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                if (networkInfo?.isConnected == true) {
                    val ssid = getCurrentSsid(context)
                    Log.i(TAG, "WiFi connected, SSID: $ssid")
                    rules.filter { it.triggerType == "wifi_connected" }.forEach { rule ->
                        val trigger = rule.triggerValue.trim()
                        val shouldFire = trigger == "*" || trigger.equals("any", true) ||
                            (ssid != null && ssid.equals("\"$trigger\"", ignoreCase = true))
                        if (shouldFire) {
                            fireRule(context, rule, "Connected to WiFi: ${ssid ?: "unknown"}")
                        }
                    }
                } else if (networkInfo?.isConnected == false) {
                    Log.i(TAG, "WiFi disconnected")
                    rules.filter { it.triggerType == "wifi_disconnected" }.forEach { rule ->
                        fireRule(context, rule, "WiFi disconnected")
                    }
                }
            }
        }
    }

    private fun getCurrentSsid(context: Context): String? {
        return try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo
            info.ssid
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get SSID", e)
            null
        }
    }

    private fun fireRule(context: Context, rule: AgentDatabase.Rule, status: String) {
        Log.i(TAG, "Firing rule: " + rule.name + " — " + status)
        RuleEngine.execute(context, rule, status)
    }
}

