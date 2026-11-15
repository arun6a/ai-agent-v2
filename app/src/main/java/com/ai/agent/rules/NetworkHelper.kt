package com.ai.agent.rules

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v2.5.0: NetworkHelper + OfflineRuleQueue
 *
 * Two key capabilities for offline rule support:
 *
 * 1. isOnline() — fast check if device has working internet
 *    (not just "connected to WiFi" but actually has network capability)
 *
 * 2. OfflineRuleQueue — when a SMART rule fires but device is offline,
 *    we queue it here. When network reconnects, ConnectivityReceiver
 *    processes the queue.
 *
 * Direct rules (Tier 1) don't need this — they execute tools directly,
 * no AI call required. Works 100% offline.
 *
 * Smart rules (Tier 2) need AI (cloud) — if offline, queue + retry later.
 */
object NetworkHelper {
    private const val TAG = "NetworkHelper"

    /**
     * Check if device has working internet connection.
     * Not just "WiFi connected" but actually has network capability + internet access.
     */
    fun isOnline(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        // Has internet capability AND is on WiFi or Cellular
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    }

    /**
     * Get human-readable connection status.
     */
    fun getConnectionType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "none"
        val network = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }
}

/**
 * OfflineRuleQueue — stores smart rules that fired while offline.
 * Persisted to a JSON file so they survive app restarts.
 *
 * When network reconnects, ConnectivityReceiver calls processQueue()
 * to retry each pending rule.
 */
object OfflineRuleQueue {
    private const val TAG = "OfflineRuleQueue"
    private const val QUEUE_FILE = "offline_rule_queue.json"
    private const val MAX_QUEUE_SIZE = 50  // don't queue forever

    data class PendingRule(
        val ruleName: String,
        val ruleAction: String,
        val triggerType: String,
        val notifTitle: String,
        val notifText: String,
        val queuedAt: Long = System.currentTimeMillis(),
    )

    private fun queueFile(context: Context): File {
        return File(context.filesDir, QUEUE_FILE)
    }

    /**
     * Add a rule to the offline queue.
     * If queue is full, drop oldest.
     */
    @Synchronized
    fun enqueue(context: Context, rule: PendingRule) {
        val queue = loadQueue(context).toMutableList()
        queue.add(rule)
        // Cap queue size — drop oldest
        while (queue.size > MAX_QUEUE_SIZE) {
            queue.removeAt(0)
        }
        saveQueue(context, queue)
        Log.i(TAG, "Queued offline rule: ${rule.ruleName} (queue size: ${queue.size})")
    }

    /**
     * Load all pending rules from disk.
     */
    @Synchronized
    fun loadQueue(context: Context): List<PendingRule> {
        val file = queueFile(context)
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PendingRule(
                    ruleName = o.optString("ruleName"),
                    ruleAction = o.optString("ruleAction"),
                    triggerType = o.optString("triggerType"),
                    notifTitle = o.optString("notifTitle"),
                    notifText = o.optString("notifText"),
                    queuedAt = o.optLong("queuedAt"),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load queue", e)
            emptyList()
        }
    }

    /**
     * Save queue to disk.
     */
    @Synchronized
    private fun saveQueue(context: Context, queue: List<PendingRule>) {
        try {
            val arr = JSONArray()
            queue.forEach { r ->
                arr.put(JSONObject().apply {
                    put("ruleName", r.ruleName)
                    put("ruleAction", r.ruleAction)
                    put("triggerType", r.triggerType)
                    put("notifTitle", r.notifTitle)
                    put("notifText", r.notifText)
                    put("queuedAt", r.queuedAt)
                })
            }
            queueFile(context).writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save queue", e)
        }
    }

    /**
     * Clear the queue (after processing).
     */
    @Synchronized
    fun clear(context: Context) {
        queueFile(context).delete()
        Log.i(TAG, "Queue cleared")
    }

    /**
     * Get count of pending rules.
     */
    fun size(context: Context): Int = loadQueue(context).size
}
