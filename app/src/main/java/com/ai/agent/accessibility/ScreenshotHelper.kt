package com.ai.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
import java.util.concurrent.Executor

/**
 * v3.8.16: Java helper to call AccessibilityService.takeScreenshot().
 *
 * WHY THIS EXISTS:
 * Kotlin can't directly call takeScreenshot(Executor, TakeScreenshotCallback)
 * because the compiler resolves it to a different method signature.
 * Reflection (getMethod/getDeclaredMethod) fails because Class.forName
 * loads TakeScreenshotCallback with a different class loader than what
 * getMethod expects.
 *
 * Java doesn't have these issues — it can call the method directly.
 */
object ScreenshotHelper {

    interface Callback {
        fun onSuccess(result: AccessibilityService.ScreenshotResult)
        fun onFailure(errorCode: Int)
    }

    @android.annotation.SuppressLint("NewApi")
    fun takeScreenshot(service: AccessibilityService, executor: Executor, callback: Callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback.onFailure(-1)
            return
        }
        // v3.8.16: The method signature on API 34 is:
        // takeScreenshot(int displayId, Executor, TakeScreenshotCallback)
        // The int is the display ID — use 0 (Display.DEFAULT_DISPLAY) for the main screen.
        // Previous attempts failed because they called the 2-parameter version which
        // doesn't exist on API 34 — it needs 3 parameters.
        val displayId = android.view.Display.DEFAULT_DISPLAY  // = 0
        service.takeScreenshot(displayId, executor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                callback.onSuccess(result)
            }

            override fun onFailure(errorCode: Int) {
                callback.onFailure(errorCode)
            }
        })
    }
}
