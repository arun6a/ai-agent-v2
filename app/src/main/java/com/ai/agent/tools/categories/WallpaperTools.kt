package com.ai.agent.tools.categories

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import com.ai.agent.accessibility.AgentAccessibilityService
import com.ai.agent.llm.LLMClient
import com.ai.agent.storage.AgentDatabase
import com.ai.agent.tools.ToolExecutor
import java.io.File

/**
 * v4.0.28: Wallpaper tools — set image as phone wallpaper.
 *
 * Tools: setWallpaper.
 *
 * Uses Android's WallpaperManager to set any image file as the phone wallpaper.
 * Works with images in /sdcard/Pictures/, Downloads, or the app workspace.
 */
object WallpaperTools {

    suspend fun execute(
        call: LLMClient.ToolCall,
        context: Context,
        @Suppress("UNUSED_PARAMETER") service: AgentAccessibilityService?,
        @Suppress("UNUSED_PARAMETER") database: AgentDatabase
    ): ToolExecutor.ToolResult {
        return when (call.name) {
            "setWallpaper" -> {
                val path = call.args["path"] as? String
                    ?: return ToolExecutor.ToolResult(false, "missing path. Provide the image file path (e.g., /storage/emulated/0/Pictures/car_wallpaper.jpg)")
                val which = call.args["which"] as? String ?: "both"  // "home", "lock", or "both"

                try {
                    val file = File(path)
                    if (!file.exists()) {
                        return ToolExecutor.ToolResult(false, "file not found: $path. Use listFiles to find the image first.")
                    }
                    val bitmap = BitmapFactory.decodeFile(path)
                        ?: return ToolExecutor.ToolResult(false, "could not decode image (not a valid image file): $path")

                    val wm = WallpaperManager.getInstance(context)
                    val flag = when (which.lowercase()) {
                        "home" -> WallpaperManager.FLAG_SYSTEM
                        "lock" -> WallpaperManager.FLAG_LOCK
                        else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                    }
                    wm.setBitmap(bitmap, null, true, flag)

                    ToolExecutor.ToolResult(true,
                        "✅ Wallpaper set successfully!\n" +
                        "   File: $path\n" +
                        "   Applied to: ${when (which.lowercase()) { "home" -> "Home screen"; "lock" -> "Lock screen"; else -> "Home + Lock screen" }}\n" +
                        "   Image size: ${bitmap.width}×${bitmap.height}"
                    )
                } catch (e: SecurityException) {
                    ToolExecutor.ToolResult(false,
                        "Permission denied: SET_WALLPAPER. This shouldn't happen (it's a normal permission). " +
                        "Details: ${e.message}")
                } catch (e: Exception) {
                    ToolExecutor.ToolResult(false, "Failed to set wallpaper: ${e.message}")
                }
            }
            else -> ToolExecutor.ToolResult(false, "Unknown wallpaper tool: ${call.name}")
        }
    }
}
