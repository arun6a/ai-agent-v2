package com.ai.agent.rules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.ai.agent.storage.AgentDatabase

/**
 * v3.0.0: RuleTriggerManager — registers ALL trigger receivers at runtime.
 *
 * Android 8+ (API 26+) blocks most implicit system broadcasts from manifest-registered receivers.
 * They MUST be registered at runtime via registerReceiver().
 *
 * This ONE file replaces:
 * - BatteryRuleReceiver (manifest → runtime)
 * - HeadsetReceiver (manifest → runtime)
 * - WiFiReceiver (manifest → runtime)
 * - ScreenReceiver (already runtime, but in AgentService)
 * - ConnectivityReceiver (manifest → runtime)
 *
 * Each trigger is a BroadcastReceiver that:
 * 1. Fires on the system event
 * 2. Finds matching rules in DB
 * 3. Calls RuleEngine.execute(context, rule) → DONE
 *
 * No WorkManager, no AgentService, no wrapping.
 */
object RuleTriggerManager {
    private const val TAG = "RuleTriggerManager"

    private var batteryReceiver: BroadcastReceiver? = null
    private var headsetReceiver: BroadcastReceiver? = null
    private var wifiReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var connectivityReceiver: BroadcastReceiver? = null
    @Volatile
    private var registered = false
    // v3.0.4: Store app context so we can re-register if needed
    private var appContext: Context? = null

    /**
     * Register ALL trigger receivers. Called by NotificationListener.onListenerConnected()
     * (primary — MIUI can't kill NotificationListener) + AgentService.onCreate() (fallback).
     * v3.0.4: Uses per-receiver null check instead of global flag.
     * This way, if AgentService registers first then dies, NotificationListener can RE-register
     * the receivers (since unregisterAll sets them to null).
     */
    fun registerAll(context: Context) {
        // v3.0.4: Use applicationContext so receivers survive even if the calling component dies
        val ctx = context.applicationContext
        appContext = ctx

        Log.i(TAG, "=== REGISTERING ALL TRIGGERS (via ${context.javaClass.simpleName}) ===")

        // v3.0.4: Register each receiver individually — skip if already registered (not null)
        // This allows re-registration after AgentService death (unregisterAll sets to null)

        // === Battery / Power (charging, discharging, battery_low) ===
        if (batteryReceiver == null) {
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    handleBatteryEvent(ctx, intent)
                }
            }
            registerReceiver(ctx, batteryReceiver!!, IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            })
            Log.i(TAG, "✓ Battery/Power trigger registered")
        } else {
            Log.i(TAG, "→ Battery receiver already registered — skipping")
        }

        // === Headset (headset_connected, headset_disconnected) ===
        if (headsetReceiver == null) {
            headsetReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    handleHeadsetEvent(ctx, intent)
                }
            }
            registerReceiver(ctx, headsetReceiver!!, IntentFilter().apply {
                addAction(Intent.ACTION_HEADSET_PLUG)
            })
            Log.i(TAG, "✓ Headset trigger registered")
        } else {
            Log.i(TAG, "→ Headset receiver already registered — skipping")
        }

        // === WiFi (wifi_connected, wifi_disconnected) ===
        if (wifiReceiver == null) {
            wifiReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    handleWifiEvent(ctx, intent)
                }
            }
            registerReceiver(ctx, wifiReceiver!!, IntentFilter().apply {
                addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION)
            })
            Log.i(TAG, "✓ WiFi trigger registered")
        } else {
            Log.i(TAG, "→ WiFi receiver already registered — skipping")
        }

        // === Screen (screen_on, screen_off, user_unlocked) ===
        if (screenReceiver == null) {
            screenReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    handleScreenEvent(ctx, intent)
                }
            }
            registerReceiver(ctx, screenReceiver!!, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            })
            Log.i(TAG, "✓ Screen trigger registered")
        } else {
            Log.i(TAG, "→ Screen receiver already registered — skipping")
        }

        // === Connectivity (for offline rule queue processing) ===
        if (connectivityReceiver == null) {
            connectivityReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    handleConnectivityEvent(ctx, intent)
                }
            }
            registerReceiver(ctx, connectivityReceiver!!, IntentFilter().apply {
                addAction(android.net.ConnectivityManager.CONNECTIVITY_ACTION)
            })
            Log.i(TAG, "✓ Connectivity trigger registered")
        } else {
            Log.i(TAG, "→ Connectivity receiver already registered — skipping")
        }

        registered = true
        Log.i(TAG, "=== ALL TRIGGERS REGISTERED ===")
    }

    /**
     * Unregister all receivers. Called by AgentService.onDestroy().
     */
    fun unregisterAll(context: Context) {
        if (!registered) return
        Log.i(TAG, "Unregistering all triggers...")
        for (rcv in listOf(batteryReceiver, headsetReceiver, wifiReceiver, screenReceiver, connectivityReceiver)) {
            try { rcv?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        }
        batteryReceiver = null
        headsetReceiver = null
        wifiReceiver = null
        screenReceiver = null
        connectivityReceiver = null
        registered = false
    }

    // === EVENT HANDLERS ===

    private fun handleBatteryEvent(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                Log.i(TAG, "⚡ POWER_CONNECTED")
                fireRules(context, "charging", "Phone is now charging")
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                Log.i(TAG, "🔌 POWER_DISCONNECTED")
                fireRules(context, "discharging", "Phone stopped charging")
            }
            Intent.ACTION_BATTERY_CHANGED -> {
                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                if (percent >= 0) {
                    Log.d(TAG, "🔋 Battery: $percent%")
                    // Check battery_low rules
                    fireBatteryLowRules(context, percent)
                    // Check battery_full rules
                    if (percent >= 100) {
                        fireRules(context, "battery_full", "Battery full (100%)")
                    }
                }
            }
        }
    }

    private fun handleHeadsetEvent(context: Context, intent: Intent) {
        val state = intent.getIntExtra("state", -1)
        when (state) {
            1 -> {
                Log.i(TAG, "🎧 Headset connected")
                fireRules(context, "headset_connected", "Headset connected")
            }
            0 -> {
                Log.i(TAG, "🎧 Headset disconnected")
                fireRules(context, "headset_disconnected", "Headset disconnected")
            }
        }
    }

    private fun handleWifiEvent(context: Context, intent: Intent) {
        val connManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val networkInfo = connManager.activeNetworkInfo
        if (networkInfo?.isConnected == true && networkInfo.type == android.net.ConnectivityManager.TYPE_WIFI) {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ssid = wifiManager.connectionInfo?.ssid?.removePrefix("\"")?.removeSuffix("\"")
            Log.i(TAG, "📶 WiFi connected: $ssid")
            fireRules(context, "wifi_connected", "Connected to WiFi: ${ssid ?: "unknown"}")
        } else if (networkInfo == null || !networkInfo.isConnected) {
            Log.i(TAG, "📶 WiFi disconnected")
            fireRules(context, "wifi_disconnected", "WiFi disconnected")
        }
    }

    private fun handleScreenEvent(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                Log.i(TAG, "📱 Screen ON")
                fireRules(context, "screen_on", "Screen turned on")
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.i(TAG, "📱 Screen OFF")
                fireRules(context, "screen_off", "Screen turned off")
            }
            Intent.ACTION_USER_PRESENT -> {
                Log.i(TAG, "📱 User unlocked")
                fireRules(context, "user_unlocked", "User unlocked the phone")
            }
        }
    }

    private fun handleConnectivityEvent(context: Context, intent: Intent) {
        if (NetworkHelper.isOnline(context)) {
            Log.i(TAG, "🌐 Network online — processing offline queue")
            processOfflineQueue(context)
        }
    }

    // === HELPERS ===

    /**
     * Fire all rules matching the given triggerType.
     */
    private fun fireRules(context: Context, triggerType: String, contextStr: String) {
        try {
            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter { it.triggerType == triggerType }
            if (rules.isEmpty()) {
                Log.d(TAG, "No rules for $triggerType")
                return
            }
            Log.i(TAG, "Found ${rules.size} rule(s) for $triggerType")
            for (rule in rules) {
                RuleEngine.execute(context, rule, contextStr)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fire rules for $triggerType", e)
        }
    }

    /**
     * Check battery_low rules with threshold.
     */
    private fun fireBatteryLowRules(context: Context, percent: Int) {
        try {
            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter { it.triggerType == "battery_low" }
            val prefs = context.getSharedPreferences("battery_rule_state", Context.MODE_PRIVATE)
            for (rule in rules) {
                val threshold = rule.triggerValue.toIntOrNull() ?: continue
                if (percent < threshold) {
                    val lastFired = prefs.getLong("last_fired_${rule.id}", 0L)
                    val now = System.currentTimeMillis()
                    if (now - lastFired > 30 * 60 * 1000L) {  // 30 min cooldown
                        prefs.edit().putLong("last_fired_${rule.id}", now).apply()
                        RuleEngine.execute(context, rule, "Battery at $percent% (below $threshold%)")
                    }
                } else {
                    prefs.edit().remove("last_fired_${rule.id}").apply()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fire battery_low rules", e)
        }
    }

    /**
     * Process offline rule queue when network reconnects.
     */
    private fun processOfflineQueue(context: Context) {
        try {
            val queue = OfflineRuleQueue.loadQueue(context)
            if (queue.isEmpty()) return
            Log.i(TAG, "Processing ${queue.size} queued rules")
            for (pending in queue) {
                val fakeRule = AgentDatabase.Rule(
                    id = 0L,
                    name = pending.ruleName,
                    triggerType = pending.triggerType,
                    triggerValue = "",
                    action = pending.ruleAction,
                    enabled = true,
                    days = null,
                )
                RuleEngine.execute(context, fakeRule, "from_offline_queue: ${pending.notifText}")
            }
            OfflineRuleQueue.clear(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process offline queue", e)
        }
    }

    /**
     * Register a receiver with the correct flags for Android 13+ (TIRAMISU).
     */
    private fun registerReceiver(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }
}
