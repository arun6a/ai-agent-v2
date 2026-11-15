package com.ai.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PathResolver — resolves relative paths against Aira's workspace.
 *
 * This was the source of 3 failures in the v4.0.97 log:
 *   writeFile(path="Aira/test1.txt") → ENOENT (relative path not resolved)
 *   writeFile(path="Aira/test2.txt") → ENOENT
 *   listFiles(path="Aira") → Directory not found
 *
 * v4.0.98 fix: Auto-resolve relative paths.
 * These tests verify the fix + edge cases.
 */
class PathResolverTest {

    private val WORKSPACE = "/storage/emulated/0/Aira"

    // === ABSOLUTE PATHS (unchanged) ===

    @Test
    fun `absolute path with sdcard gets normalized`() {
        // /sdcard/ is normalized to /storage/emulated/0/ (Android symlink)
        val result = PathResolver.resolve("/sdcard/test.txt", WORKSPACE)
        assertEquals("/storage/emulated/0/test.txt", result)
    }

    @Test
    fun `absolute path with full storage path returned as-is`() {
        val path = "/storage/emulated/0/Aira/test.txt"
        assertEquals(path, PathResolver.resolve(path, WORKSPACE))
    }

    @Test
    fun `root path returned as-is`() {
        assertEquals("/", PathResolver.resolve("/", WORKSPACE))
    }

    // === RELATIVE PATHS (resolved against workspace) ===

    @Test
    fun `relative path resolved against workspace`() {
        assertEquals("$WORKSPACE/test.txt", PathResolver.resolve("test.txt", WORKSPACE))
    }

    @Test
    fun `relative path with subdirectory resolved`() {
        assertEquals("$WORKSPACE/Aira/test.txt", PathResolver.resolve("Aira/test.txt", WORKSPACE))
    }

    @Test
    fun `relative path with nested dirs resolved`() {
        assertEquals("$WORKSPACE/a/b/c.txt", PathResolver.resolve("a/b/c.txt", WORKSPACE))
    }

    // === NULL PATH (returns workspace root) ===

    @Test
    fun `null path returns workspace root`() {
        assertEquals(WORKSPACE, PathResolver.resolve(null, WORKSPACE))
    }

    // === /sdcard/ NORMALIZATION ===

    @Test
    fun `sdcard path normalized to storage emulated`() {
        // /sdcard/ and /storage/emulated/0/ are the same on Android
        val result = PathResolver.resolve("/sdcard/Aira/test.txt", WORKSPACE)
        assertEquals("/storage/emulated/0/Aira/test.txt", result)
    }

    @Test
    fun `sdcard root normalized`() {
        assertEquals("/storage/emulated/0/", PathResolver.resolve("/sdcard/", WORKSPACE))
    }

    // === EDGE CASES ===

    @Test
    fun `empty string returns workspace with trailing slash`() {
        // Edge case: empty string is relative, so gets prefixed
        assertEquals("$WORKSPACE/", PathResolver.resolve("", WORKSPACE))
    }

    @Test
    fun `path with only slashes returned as-is`() {
        assertEquals("///", PathResolver.resolve("///", WORKSPACE))
    }

    @Test
    fun `relative path with leading dot`() {
        assertEquals("$WORKSPACE/./test.txt", PathResolver.resolve("./test.txt", WORKSPACE))
    }

    @Test
    fun `relative path with double dot`() {
        assertEquals("$WORKSPACE/../test.txt", PathResolver.resolve("../test.txt", WORKSPACE))
    }

    // === IS ABSOLUTE ===

    @Test
    fun `isAbsolute true for slash paths`() {
        assertTrue(PathResolver.isAbsolute("/test"))
        assertTrue(PathResolver.isAbsolute("/sdcard/Aira"))
    }

    @Test
    fun `isAbsolute false for relative paths`() {
        assertFalse(PathResolver.isAbsolute("test"))
        assertFalse(PathResolver.isAbsolute("Aira/test.txt"))
    }

    @Test
    fun `isAbsolute false for empty string`() {
        assertFalse(PathResolver.isAbsolute(""))
    }

    // === REAL-WORLD LOG INPUTS ===

    @Test
    fun `real-world input Aira test1 txt resolves correctly`() {
        // This was the first attempt in logs that FAILED before v4.0.98
        val result = PathResolver.resolve("Aira/test1.txt", WORKSPACE)
        assertEquals("$WORKSPACE/Aira/test1.txt", result)
    }

    @Test
    fun `real-world input sdcard Aira test1 txt normalizes`() {
        // This was the second attempt in logs that SUCCEEDED
        val result = PathResolver.resolve("/sdcard/Aira/test1.txt", WORKSPACE)
        assertEquals("/storage/emulated/0/Aira/test1.txt", result)
    }
}
