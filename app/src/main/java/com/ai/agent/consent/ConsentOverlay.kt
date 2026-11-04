package com.ai.agent.consent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ai.agent.util.LogCapture

/**
 * v4.0.82: ConsentOverlay — shows consent dialog OVER any app using WindowManager.
 *
 * When SYSTEM_ALERT_WINDOW permission is granted:
 * - Shows a system overlay with the consent request
 * - User can approve/deny from ANY app (not just Aira)
 * - Two buttons: "Allow" (green) + "Deny" (red)
 *
 * When permission is NOT granted:
 * - Falls back to the in-app Compose ConsentDialog (existing behavior)
 * - ConsentManager.requestConsent() handles both paths
 *
 * Flow:
 * 1. ConsentManager.requestConsent() is called (from ToolExecutor, IO thread)
 * 2. Checks if overlay permission is granted
 * 3. If YES: ConsentOverlay.show() — displays system overlay over all apps
 * 4. If NO: sets pendingRequest (StateFlow) — ChatScreen shows Compose dialog
 * 5. User taps Allow/Deny → ConsentManager.resolve(approved)
 * 6. Overlay hides, requestConsent() returns the result
 */
object ConsentOverlay {
    private const val TAG = "ConsentOverlay"

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var isVisible = false
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Show the consent dialog as a system overlay (over all apps).
     * Returns true if shown, false if overlay permission not granted.
     */
    fun show(context: Context, request: ConsentManager.ConsentRequest): Boolean {
        // Check overlay permission
        if (!android.provider.Settings.canDrawOverlays(context)) {
            LogCapture.w(TAG, "Overlay permission not granted — falling back to in-app dialog")
            return false
        }

        hide()  // remove any existing overlay
        handler.post {
            try {
                windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

                val density = context.resources.displayMetrics.density
                val screenWidth = context.resources.displayMetrics.widthPixels

                // === Build the overlay UI programmatically ===

                // Main container
                val container = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = 16 * density
                        setColor(Color.parseColor("#FF1A1A2E"))
                        setStroke(1, Color.parseColor("#FF6C63FF"))
                    }
                    setPadding(
                        (20 * density).toInt(), (16 * density).toInt(),
                        (20 * density).toInt(), (16 * density).toInt()
                    )
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                }

                // Title
                val title = TextView(context).apply {
                    text = "🔒 ${request.toolName} requires approval"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, (8 * density).toInt())
                }
                container.addView(title)

                // Description
                val desc = TextView(context).apply {
                    text = request.description
                    setTextColor(Color.parseColor("#FFB0B0C0"))
                    textSize = 13f
                    setPadding(0, 0, 0, (8 * density).toInt())
                }
                container.addView(desc)

                // Details (in a scrollable view)
                if (request.details.isNotEmpty()) {
                    val detailsText = TextView(context).apply {
                        text = request.details
                        setTextColor(Color.parseColor("#FF808090"))
                        textSize = 11f
                        setPadding(
                            (8 * density).toInt(), (8 * density).toInt(),
                            (8 * density).toInt(), (8 * density).toInt()
                        )
                        background = GradientDrawable().apply {
                            cornerRadius = 8 * density
                            setColor(Color.parseColor("#FF0D0D1A"))
                        }
                    }
                    val scrollView = ScrollView(context).apply {
                        addView(detailsText)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (100 * density).toInt()
                        )
                    }
                    container.addView(scrollView)
                }

                    // Spacer
                    val spacer = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, (12 * density).toInt()
                        )
                    }
                    container.addView(spacer)

                    // Button row
                    val buttonRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                    }

                    // Deny button
                    val denyBtn = Button(context).apply {
                        text = "Deny"
                        setTextColor(Color.parseColor("#FFFF6B6B"))
                        background = GradientDrawable().apply {
                            cornerRadius = 10 * density
                            setColor(Color.parseColor("#FF2D1B1B"))
                            setStroke(1, Color.parseColor("#66FF6B6B"))
                        }
                        setOnClickListener {
                            Log.i(TAG, "Consent DENIED for ${request.toolName}")
                            ConsentManager.resolve(false)
                        }
                        setPadding(
                            (24 * density).toInt(), (8 * density).toInt(),
                            (24 * density).toInt(), (8 * density).toInt()
                        )
                    }
                    buttonRow.addView(denyBtn)

                    // Spacer between buttons
                    val btnSpacer = View(context).apply {
                        layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), 1)
                    }
                    buttonRow.addView(btnSpacer)

                    // Allow button
                    val allowBtn = Button(context).apply {
                        text = "Allow"
                        setTextColor(Color.WHITE)
                        background = GradientDrawable().apply {
                            cornerRadius = 10 * density
                            setColor(Color.parseColor("#FF6C63FF"))
                        }
                        setOnClickListener {
                            Log.i(TAG, "Consent APPROVED for ${request.toolName}")
                            ConsentManager.resolve(true)
                        }
                        setPadding(
                            (24 * density).toInt(), (8 * density).toInt(),
                            (24 * density).toInt(), (8 * density).toInt()
                        )
                    }
                    buttonRow.addView(allowBtn)

                    container.addView(buttonRow)

                    // === Layout params for the overlay window ===
                    val overlayType = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_PHONE
                    }

                    val params = WindowManager.LayoutParams(
                        (screenWidth * 0.9).toInt(),  // 90% of screen width
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        overlayType,
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.CENTER
                    }

                    overlayView = container
                    windowManager?.addView(container, params)
                    isVisible = true
                    Log.i(TAG, "Consent overlay shown for ${request.toolName}")

            } catch (e: Exception) {
                LogCapture.e(TAG, "Failed to show consent overlay: ${e.message}")
                isVisible = false
            }
        }
        return true
    }

    /**
     * Hide the overlay (if showing).
     */
    fun hide() {
        if (!isVisible) return
        handler.post {
            try {
                overlayView?.let { view ->
                    windowManager?.removeView(view)
                }
                overlayView = null
                isVisible = false
                Log.i(TAG, "Consent overlay hidden")
            } catch (e: Exception) {
                LogCapture.w(TAG, "Failed to hide consent overlay: ${e.message}")
                isVisible = false
            }
        }
    }

    fun isShowing(): Boolean = isVisible
}
