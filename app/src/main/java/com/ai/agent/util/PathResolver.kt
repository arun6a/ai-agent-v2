package com.ai.agent.util

/**
 * v4.0.99: Pure-logic path resolver — extracted from FileTools for unit testing.
 *
 * Resolves relative paths against Aira's workspace directory:
 *   "Aira/test.txt"        → "/storage/emulated/0/Aira/Aira/test.txt"
 *   "/sdcard/Aira/test.txt" → "/sdcard/Aira/test.txt" (absolute, unchanged)
 *   "test.txt"              → "/storage/emulated/0/Aira/test.txt"
 *   null                    → "/storage/emulated/0/Aira" (workspace root)
 *
 * Also handles /sdcard/ → /storage/emulated/0/ normalization.
 */
object PathResolver {

    /**
     * Resolve a path against Aira's workspace directory.
     * - Absolute paths (starting with /) are returned as-is (with /sdcard/ normalized)
     * - Relative paths are prefixed with WORKSPACE_DIR
     * - null returns WORKSPACE_DIR
     */
    fun resolve(rawPath: String?, workspaceDir: String = com.ai.agent.Config.WORKSPACE_DIR): String {
        if (rawPath == null) return workspaceDir
        // v4.0.99: Normalize /sdcard/ → /storage/emulated/0/ (they're the same on Android)
        val normalized = rawPath.replace(Regex("^/sdcard/"), "/storage/emulated/0/")
        return if (normalized.startsWith("/")) {
            normalized
        } else {
            "$workspaceDir/$normalized"
        }
    }

    /**
     * Check if a path is absolute (starts with /).
     */
    fun isAbsolute(path: String): Boolean = path.startsWith("/")
}
