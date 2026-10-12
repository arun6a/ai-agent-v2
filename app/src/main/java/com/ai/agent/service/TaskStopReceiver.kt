package com.ai.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * v2.2.2: Receives "Stop Task" broadcasts from the foreground notification.
 * When the user taps "Stop Task" in the notification bar, this receiver
 * sets a static flag that the agent loop checks on every iteration.
 */
class TaskStopReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_TASK = "com.ai.agent.STOP_TASK"

        @Volatile
        var stopRequested = false

        fun requestStop() {
            stopRequested = true
            Log.i("TaskStopReceiver", "Stop requested via notification")
        }

        fun reset() {
            stopRequested = false
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == ACTION_STOP_TASK) {
            requestStop()
        }
    }
}
