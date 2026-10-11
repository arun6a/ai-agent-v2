package com.ai.agent.rules

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.ai.agent.storage.AgentDatabase

/**
 * Location trigger (v6.1.0) — checks if user entered/exited a saved location.
 *
 * Uses last known location + compares with rule's triggerValue (lat,lng,radius).
 * Scheduled by AlarmManager to check periodically.
 *
 * triggerType: "location_entered" or "location_exited"
 * triggerValue: "lat,lng,radius_meters" (e.g., "12.97,77.59,500")
 */
class LocationReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LocationReceiver"
        const val ACTION_LOCATION_CHECK = "com.ai.agent.LOCATION_CHECK"
    }

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOCATION_CHECK) return
        Log.i(TAG, "Location check triggered")
        checkLocation(context)
        scheduleNextCheck(context)
    }

    @SuppressLint("MissingPermission")
    private fun checkLocation(context: Context) {
        if (!hasLocationPermission(context)) {
            Log.w(TAG, "No location permission — skipping check")
            return
        }
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val lastLocation = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                ?: return

            val db = AgentDatabase(context)
            val rules = db.getRules(enabledOnly = true).filter {
                it.triggerType == "location_entered" || it.triggerType == "location_exited"
            }

            for (rule in rules) {
                val parts = rule.triggerValue.split(",")
                if (parts.size >= 3) {
                    val lat = parts[0].toDoubleOrNull() ?: continue
                    val lng = parts[1].toDoubleOrNull() ?: continue
                    val radius = parts[2].toDoubleOrNull() ?: 500.0

                    val results = FloatArray(1)
                    Location.distanceBetween(lastLocation.latitude, lastLocation.longitude, lat, lng, results)
                    val distance = results[0]
                    val isInside = distance <= radius

                    if (rule.triggerType == "location_entered" && isInside) {
                        val locInfo = "Entered location (lat=$lat, lng=$lng, distance=${"%.0f".format(distance)}m)"
                        RuleTriggerReceiver.triggerRuleAction(context, rule, locInfo)
                        db.logAction("Location entered: ${rule.name}")
                    } else if (rule.triggerType == "location_exited" && !isInside) {
                        val locInfo = "Exited location (lat=$lat, lng=$lng, distance=${"%.0f".format(distance)}m)"
                        RuleTriggerReceiver.triggerRuleAction(context, rule, locInfo)
                        db.logAction("Location exited: ${rule.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check location", e)
        }
    }

    private fun hasLocationPermission(context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
               PackageManager.PERMISSION_GRANTED
    }

    private fun scheduleNextCheck(context: Context) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val intent = Intent(context, LocationReceiver::class.java).apply {
                action = ACTION_LOCATION_CHECK
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 998, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M)
                    android.app.PendingIntent.FLAG_IMMUTABLE else 0
            )
            val nextCheck = System.currentTimeMillis() + (5 * 60 * 1000) // 5 minutes
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, nextCheck, pendingIntent)
            } else {
                alarmManager.setExact(android.app.AlarmManager.RTC_WAKEUP, nextCheck, pendingIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule next location check", e)
        }
    }
}
