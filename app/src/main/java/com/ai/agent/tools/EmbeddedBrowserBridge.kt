package com.ai.agent.tools

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.ref.WeakReference

/**
 * v2.3.0: Static bridge between ToolExecutor (background thread) and
 * ChatViewModel (UI thread). Allows browserOpen() to trigger the
 * embedded WebView overlay without having a direct reference to the ViewModel.
 *
 * ChatViewModel registers itself via register() in init{}.
 * ToolExecutor calls show(url) which posts to the main thread.
 */
object EmbeddedBrowserBridge {
    private const val TAG = "EmbeddedBrowserBridge"

    // WeakReference so we don't leak the ViewModel
    private var viewModelRef: WeakReference<Any>? = null
    private var showMethod: java.lang.reflect.Method? = null

    /**
     * Register the ChatViewModel so we can call showEmbeddedWebView(url) on it.
     * Called by ChatViewModel.init{}.
     */
    fun register(viewModel: Any) {
        viewModelRef = WeakReference(viewModel)
        try {
            showMethod = viewModel.javaClass.getMethod("showEmbeddedWebView", String::class.java)
            Log.i(TAG, "ChatViewModel registered for embedded browser bridge")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find showEmbeddedWebView method", e)
        }
    }

    /**
     * Unregister (called by ChatViewModel.onCleared()).
     */
    fun unregister() {
        viewModelRef = null
        showMethod = null
    }

    /**
     * Show the embedded WebView overlay with a URL.
     * Posts to the main thread (safe to call from IO dispatcher).
     */
    fun show(url: String) {
        val vm = viewModelRef?.get() ?: run {
            Log.w(TAG, "ChatViewModel not registered — cannot show embedded browser")
            return
        }
        val method = showMethod ?: run {
            Log.w(TAG, "showEmbeddedWebView method not found")
            return
        }

        // Must call on main thread (Compose state update)
        Handler(Looper.getMainLooper()).post {
            try {
                method.invoke(vm, url)
                Log.i(TAG, "Embedded browser bridge: show($url) called on ViewModel")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to invoke showEmbeddedWebView", e)
            }
        }
    }
}
