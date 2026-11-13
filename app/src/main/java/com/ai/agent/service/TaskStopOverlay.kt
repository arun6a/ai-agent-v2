package com.ai.agent.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.ai.agent.util.LogCapture

/**
 * v4.0.10: TaskStopOverlay — a floating "Stop" button that appears over ALL apps
 * when a task is running. Positioned at top-center of screen edge.
 *
 * Uses the existing SYSTEM_ALERT_WINDOW permission (already in manifest).
 * Uses TYPE_APPLICATION_OVERLAY (same as old OverlayManager).
 *
 * - show(): Called when agent loop starts. Button appears at top-center.
 * - hide(): Called when task completes/stops/times out. Button disappears.
 * - Tapping the button sets stopRequested=true, which ChatViewModel checks
 *   in its while loop condition to stop the task.
 * - Draggable: user can drag the button to reposition it.
 */
object TaskStopOverlay {
    private const val TAG = "TaskStopOverlay"

    @Volatile
    var stopRequested: Boolean = false
        private set

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isVisible = false
    private val handler = Handler(Looper.getMainLooper())
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    /**
     * Show the floating Stop button over all apps.
     * Call this when the agent loop starts.
     */
    fun show(context: Context) {
        if (isVisible) return

        // Check overlay permission
        if (!android.provider.Settings.canDrawOverlays(context)) {
            LogCapture.w(TAG, "SYSTEM_ALERT_WINDOW not granted — stop overlay won't show")
            return
        }

        stopRequested = false
        handler.post {
            try {
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                // Build a pill-shaped button programmatically (no XML needed)
                val layout = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 60f  // pill shape
                        setColor(Color.parseColor("#EF4444"))  // red-500
                    }
                    background = bg
                    setPadding(56, 28, 56, 28)  // px padding
                }

                val icon = TextView(context).apply {
                    text = "■"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                }
                val label = TextView(context).apply {
                    text = " Stop"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                }
                layout.addView(icon)
                layout.addView(label)

                // Touch handler: tap = stop, drag = move
                layout.setOnTouchListener { v, event ->
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = (overlayView?.layoutParams as? WindowManager.LayoutParams)?.x ?: 0
                            initialY = (overlayView?.layoutParams as? WindowManager.LayoutParams)?.y ?: 0
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val params = overlayView?.layoutParams as? WindowManager.LayoutParams
                            if (params != null) {
                                params.x = initialX + (event.rawX - initialTouchX).toInt()
                                params.y = initialY + (event.rawY - initialTouchY).toInt()
                                windowManager?.updateViewLayout(overlayView, params)
                            }
                            true
                        }
                        MotionEvent.ACTION_UP -> {
                            val dx = kotlin.math.abs(event.rawX - initialTouchX)
                            val dy = kotlin.math.abs(event.rawY - initialTouchY)
                            if (dx < 15 && dy < 15) {
                                // It's a tap → stop the task
                                stopRequested = true
                                LogCapture.i(TAG, "Stop button tapped — requesting task stop")
                                hide()
                            }
                            true
                        }
                        else -> false
                    }
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = 60  // 60px from top edge
                }

                overlayView = layout
                windowManager?.addView(overlayView, params)
                isVisible = true
                LogCapture.i(TAG, "Stop overlay shown (top-center, over all apps)")
            } catch (e: Exception) {
                LogCapture.e(TAG, "Failed to show stop overlay: ${e.message}")
            }
        }
    }

    /**
     * Hide the floating Stop button.
     * Call this when the task completes, stops, or times out.
     */
    fun hide() {
        handler.post {
            try {
                if (isVisible && overlayView != null) {
                    windowManager?.removeView(overlayView)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to hide stop overlay: ${e.message}")
            }
            isVisible = false
            overlayView = null
        }
    }

    /** Check if the user tapped the stop button. */
    fun isStopRequested(): Boolean = stopRequested

    /** Reset the stop flag (called after ChatViewModel reads it). */
    fun resetStopRequest() {
        stopRequested = false
    }
}
