package com.ai.agent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.provider.Settings
import android.util.Log
import com.ai.agent.util.LogCapture
import android.view.accessibility.AccessibilityNodeInfo

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentA11y"
        private var instance: AgentAccessibilityService? = null
        fun getInstance(): AgentAccessibilityService? = instance

        /**
         * Returns true only if the in-memory instance is live.
         * Use this for runtime tool access (e.g., getInstance()?.readScreen()).
         */
        fun isRunning(): Boolean = instance != null

        /**
         * Returns true if the user has enabled this accessibility service
         * in Android Settings → Accessibility. This is the SOURCE OF TRUTH for
         * permission state — the in-memory `instance` is null at app launch
         * until the system binds the service (race condition).
         *
         * Use this in permission-check UI flows (e.g., checkAllPermissions()).
         */
        fun isEnabled(context: Context): Boolean {
            // Fast path: if the instance is live, it's definitely enabled.
            if (instance != null) return true
            // Slow path: query Android system setting.
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            // v2.2.2: Robust matching — try multiple formats because:
            // 1. applicationId is "com.ai.agent.v2" but namespace is "com.ai.agent"
            // 2. MIUI/custom ROMs may store the component name differently
            // 3. Some devices store just the class name without the package prefix
            val className = AgentAccessibilityService::class.java.name
            val pkgName = context.packageName  // "com.ai.agent.v2"
            val possibleFormats = listOf(
                "$pkgName/$className",           // standard: com.ai.agent.v2/com.ai.agent.accessibility.AgentAccessibilityService
                "com.ai.agent/$className",       // if namespace was used: com.ai.agent/com.ai.agent.accessibility.AgentAccessibilityService
                "com.ai.agent.v2/.accessibility.AgentAccessibilityService",  // relative from package
                "com.ai.agent/.accessibility.AgentAccessibilityService",     // relative from namespace
                className,                        // just the class name (some MIUI versions)
                "AgentAccessibilityService",     // just the short name (some custom ROMs)
            )
            val matched = possibleFormats.any { enabledServices.contains(it) }
            if (!matched) {
                android.util.Log.w(TAG, "Accessibility not found in enabled services. " +
                    "Enabled: $enabledServices | Checked: ${possibleFormats.take(3)}")
            }
            return matched
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Track the current foreground package
        event?.let {
            if (it.packageName != null && it.eventType == android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val newPackage = it.packageName.toString()
                if (newPackage != currentForegroundPackage && newPackage != "com.ai.agent") {
                    // v6.1.0: Fire app_opened / app_closed broadcasts for triggers
                    val oldPackage = currentForegroundPackage
                    currentForegroundPackage = newPackage
                    Log.i(TAG, "App changed: $oldPackage → $newPackage")
                    if (oldPackage != null) {
                        // Previous app closed
                        val closeIntent = android.content.Intent("com.ai.agent.APP_CLOSED").apply {
                            putExtra("package_name", oldPackage)
                        }
                        sendBroadcast(closeIntent)
                    }
                    // New app opened
                    val openIntent = android.content.Intent("com.ai.agent.APP_OPENED").apply {
                        putExtra("package_name", newPackage)
                    }
                    sendBroadcast(openIntent)
                } else {
                    currentForegroundPackage = newPackage
                }
            }
        }
    }
    override fun onInterrupt() { Log.w(TAG, "Interrupted") }

    private var currentForegroundPackage: String? = null

    fun getCurrentForegroundPackage(): String? {
        try {
            val root = rootInActiveWindow
            if (root != null && root.packageName != null) {
                return root.packageName.toString()
            }
        } catch (e: Exception) {
        }
        return currentForegroundPackage
    }

    /**
     * Build a structured tree of the screen — like a DOM tree for Android.
     * Returns JSON string with elements: type, text, clickable, bounds, scrollable.
     * The AI can use this to know exactly what's clickable and where it is.
     */
    fun readScreenStructured(): String {
        val root = rootInActiveWindow ?: return "{\"error\":\"screen is null\"}"
        val sb = StringBuilder()
        sb.append("{\"app\":\"${root.packageName ?: "unknown"}\",\"elements\":[")
        val state = intArrayOf(0) // state[0] = element count
        collectElements(root, sb, state, 15, 100)
        sb.append("]}")
        return sb.toString()
    }

    private fun collectElements(
        node: AccessibilityNodeInfo?,
        sb: StringBuilder,
        state: IntArray,
        maxDepth: Int,
        maxElements: Int
    ) {
        if (node == null || maxDepth <= 0 || state[0] >= maxElements) return
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val clickable = node.isClickable
        val scrollable = node.isScrollable
        val className = node.className?.toString() ?: ""

        if (text.isNotEmpty() || desc.isNotEmpty() || clickable || scrollable) {
            if (state[0] > 0) sb.append(",")
            state[0]++

            val type = when {
                className.contains("Button") || clickable -> "button"
                className.contains("EditText") -> "input"
                className.contains("TextView") || text.isNotEmpty() -> "text"
                className.contains("ScrollView") || scrollable -> "scroll"
                className.contains("ImageView") -> "image"
                else -> "view"
            }

            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            val label = if (text.isNotEmpty()) text else desc

            sb.append("{\"type\":\"$type\",\"text\":\"${escapeJson(label)}\",\"clickable\":$clickable,\"scrollable\":$scrollable,\"bounds\":\"${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}\"}")
        }

        for (i in 0 until node.childCount) {
            collectElements(node.getChild(i), sb, state, maxDepth - 1, maxElements)
        }
    }

    private fun escapeJson(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "").replace("\t", " ")
    }

    override fun onDestroy() {
        instance = null
        Log.i(TAG, "Destroyed")
        super.onDestroy()
    }

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "(screen is null)"
        val texts = mutableListOf<String>()
        collectTexts(root, texts)
        return if (texts.isEmpty()) "(no text on screen)" else texts.joinToString("\n")
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) texts.add(text)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && desc != text) texts.add("[$desc]")
        for (i in 0 until node.childCount) collectTexts(node.getChild(i), texts)
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        return dispatchGesture(gesture, null, null)
    }

    fun clickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        // 1. Exact match
        findNodeByText(root, text)?.let { return performClick(it) }
        // 2. Contains match (full text)
        findNodeContainingText(root, text)?.let { return performClick(it) }
        // v3.8.1: 3. Try shorter substrings — YouTube splits titles across nodes
        // e.g., brain passes "BLACKPINK - 'Pink Venom' M/V" but a11y has "Pink Venom" in one node
        if (text.length > 10) {
            // Try the first 20 chars
            val short = text.take(20)
            findNodeContainingText(root, short)?.let { return performClick(it) }
            // Try words after " - " (common in YouTube titles: "Artist - Song")
            val parts = text.split(" - ", " | ", " — ")
            for (part in parts) {
                if (part.length >= 5) {
                    findNodeContainingText(root, part)?.let { return performClick(it) }
                }
            }
        }
        return false
    }

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        if (text.equals(node.text?.toString(), true) || text.equals(node.contentDescription?.toString(), true)) return node
        for (i in 0 until node.childCount) { node.getChild(i)?.let { findNodeByText(it, text)?.let { return it } } }
        return null
    }

    private fun findNodeContainingText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nt = node.text?.toString() ?: ""
        val nd = node.contentDescription?.toString() ?: ""
        if (text.length >= 5) {
            if (nt.contains(text, true) || nd.contains(text, true)) return node
            val st = text.take(15)
            if (nt.contains(st, true) || nd.contains(st, true)) return node
        }
        for (i in 0 until node.childCount) { node.getChild(i)?.let { findNodeContainingText(it, text)?.let { return it } } }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var parent = node.parent; var d = 0
        while (parent != null && d < 5) { if (parent.isClickable) return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK); parent = parent.parent; d++ }
        return false
    }

    fun type(text: String): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = android.os.Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun showKeyboard(): Boolean {
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val rect = Rect(); focused.getBoundsInScreen(rect)
        if (!rect.isEmpty) { tap(rect.exactCenterX(), rect.exactCenterY()); return true }
        return false
    }

    fun findAndClickSubmit(): Boolean {
        val root = rootInActiveWindow ?: return false
        listOf("Search","Go","Send","Submit","Done","Next").forEach { findNodeByText(root, it)?.let { if (performClick(it)) return true } }
        listOf("Search","Submit","Go","Send").forEach { findNodeByDescription(root, it)?.let { if (performClick(it)) return true } }
        return false
    }

    private fun findNodeByDescription(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        if (desc.equals(node.contentDescription?.toString(), true)) return node
        for (i in 0 until node.childCount) { node.getChild(i)?.let { findNodeByDescription(it, desc)?.let { return it } } }
        return null
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun pressEnter(): Boolean = tap(970f, 2230f)

    fun submitInput(): Boolean {
        showKeyboard(); Thread.sleep(500)
        listOf(Pair(970f, 2150f), Pair(970f, 2200f), Pair(970f, 2100f), Pair(1000f, 2150f)).forEach { (x, y) ->
            tap(x, y); Thread.sleep(500)
            if (rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) == null) return true
        }
        return findAndClickSubmit()
    }

    fun launchApp(packageName: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); true } else false
        } catch (e: Exception) { false }
    }

    fun scrollDown(): Boolean = swipe(540f, 1800f, 540f, 400f, 400)
    fun scrollUp(): Boolean = swipe(540f, 400f, 540f, 1800f, 400)
    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    // === SCREENSHOT (Vision) ===

    @android.annotation.SuppressLint("NewApi")
    fun captureScreen(callback: (String?) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
            LogCapture.w(TAG, "Screenshot not supported on API < 30 (current: ${android.os.Build.VERSION.SDK_INT})")
            callback(null)
            return
        }
        try {
            // v3.8.15: Direct call instead of reflection — reflection was failing with
            // method signature mismatch on some devices.
            // takeScreenshot(Executor, TakeScreenshotCallback) is available on API 30+.
            val executor: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor()
            val screenshotCallback = object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                    try {
                        Log.i(TAG, "Screenshot received")
                        val hardwareBuffer = result.hardwareBuffer
                        val colorSpace = result.colorSpace

                        val bitmap = android.graphics.Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        if (bitmap == null) {
                            Log.e(TAG, "wrapHardwareBuffer returned null")
                            LogCapture.e(TAG, "Screenshot: wrapHardwareBuffer returned null")
                            hardwareBuffer.close()
                            callback(null)
                            return
                        }
                        Log.i(TAG, "Bitmap: ${bitmap.width}x${bitmap.height} config=${bitmap.config}")

                        // Scale down to 480px wide (save tokens for VLM, speed for ML Kit)
                        val w = bitmap.width
                        val h = bitmap.height
                        val scaled = if (w > 480) {
                            val r = 480f / w
                            android.graphics.Bitmap.createScaledBitmap(bitmap, 480, (h * r).toInt(), true)
                        } else bitmap

                        // v4.0.0 approach: compress directly (works on most devices)
                        val stream = java.io.ByteArrayOutputStream()
                        val compressOk = try {
                            scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, stream)
                        } catch (e: Exception) {
                            Log.w(TAG, "Direct compress failed: ${e.message} — trying ARGB_8888 copy fallback")
                            false
                        }

                        if (!compressOk || stream.size() == 0) {
                            // Fallback: copy to ARGB_8888 then compress
                            Log.i(TAG, "Using ARGB_8888 copy fallback for compress")
                            val softwareBitmap = bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                            if (softwareBitmap != null) {
                                val fallbackScaled = if (softwareBitmap.width > 480) {
                                    val r = 480f / softwareBitmap.width
                                    android.graphics.Bitmap.createScaledBitmap(softwareBitmap, 480, (softwareBitmap.height * r).toInt(), true)
                                } else softwareBitmap
                                stream.reset()
                                fallbackScaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, stream)
                                if (fallbackScaled !== softwareBitmap) fallbackScaled.recycle()
                                softwareBitmap.recycle()
                            }
                        }

                        val b64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
                        Log.i(TAG, "Screenshot done: ${stream.toByteArray().size} bytes, base64: ${b64.length} chars")
                        LogCapture.i(TAG, "Screenshot captured: ${b64.length} chars")

                        if (scaled !== bitmap) scaled.recycle()
                        bitmap.recycle()
                        hardwareBuffer.close()

                        if (b64.isEmpty() || b64.length < 100) {
                            Log.e(TAG, "Screenshot base64 too small (${b64.length} chars) — likely failed")
                            LogCapture.e(TAG, "Screenshot base64 too small (${b64.length} chars)")
                            callback(null)
                        } else {
                            callback(b64)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot processing failed", e)
                        LogCapture.e(TAG, "Screenshot processing failed: ${e.message}")
                        callback(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "Screenshot failed: error $errorCode")
                    LogCapture.e(TAG, "Screenshot capture FAILED: error code $errorCode (1=unsupported, 2=invalid surface, 3=no display)")
                    callback(null)
                }
            }
            // v3.8.15: Use reflection but with the correct method signature.
            // The direct call fails because Kotlin doesn't see the API 30+ overload.
            val asClass = android.accessibilityservice.AccessibilityService::class.java
            val cbClass = Class.forName("android.accessibilityservice.AccessibilityService\$TakeScreenshotCallback")
            val cbMethod = asClass.getMethod("takeScreenshot", java.util.concurrent.Executor::class.java, cbClass)
            cbMethod.invoke(this, executor, screenshotCallback)
        } catch (e: Exception) {
            Log.e(TAG, "takeScreenshot error", e)
            LogCapture.e(TAG, "takeScreenshot exception: ${e.message}")
            callback(null)
        }
    }

    /**
     * Scale + compress bitmap to JPEG base64.
     */
    private fun compressToBase64(bitmap: android.graphics.Bitmap): String {
        val w = bitmap.width
        val h = bitmap.height
        val scaled = if (w > 480) {
            val r = 480f / w
            android.graphics.Bitmap.createScaledBitmap(bitmap, 480, (h * r).toInt(), true)
        } else bitmap

        val stream = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, stream)
        val b64 = android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)
        Log.i(TAG, "Screenshot done: ${stream.toByteArray().size} bytes, base64: ${b64.length} chars")

        if (scaled !== bitmap) scaled.recycle()
        return b64
    }
}
