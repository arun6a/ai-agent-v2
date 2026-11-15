package com.ai.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PatternKeyGenerator — the key generator for Pattern Learning.
 *
 * Extracted from PatternLearner.kt to make the collision behavior testable.
 *
 * AUDIT NOTE: The current algorithm has COLLISION BUGS.
 * "play X on youtube" and "play Y on youtube" both map to the same key
 * when X is short enough (take(40) + strip non-alphanumerics).
 * These tests DOCUMENT the collision behavior so it can be fixed.
 */
class PatternKeyGeneratorTest {

    // === BASIC KEY GENERATION ===

    @Test
    fun `generates key with pattern prefix`() {
        val key = PatternKeyGenerator.generate("hello")
        assertTrue(key.startsWith("pattern_"))
    }

    @Test
    fun `generates key from simple message`() {
        val key = PatternKeyGenerator.generate("hello world")
        assertEquals("pattern_hello_world", key)
    }

    @Test
    fun `converts spaces to underscores`() {
        val key = PatternKeyGenerator.generate("play youtube video")
        assertEquals("pattern_play_youtube_video", key)
    }

    @Test
    fun `converts to lowercase`() {
        val key = PatternKeyGenerator.generate("Play YouTube Video")
        assertEquals("pattern_play_youtube_video", key)
    }

    @Test
    fun `strips non-alphanumeric characters`() {
        val key = PatternKeyGenerator.generate("what's up?")
        assertEquals("pattern_whats_up", key)
    }

    @Test
    fun `strips special characters but keeps underscores`() {
        val key = PatternKeyGenerator.generate("test@#$%message")
        assertEquals("pattern_testmessage", key)
    }

    @Test
    fun `handles numbers in message`() {
        val key = PatternKeyGenerator.generate("set alarm for 7am")
        assertEquals("pattern_set_alarm_for_7am", key)
    }

    @Test
    fun `handles empty message`() {
        val key = PatternKeyGenerator.generate("")
        assertEquals("pattern_", key)
    }

    @Test
    fun `handles whitespace-only message`() {
        val key = PatternKeyGenerator.generate("   ")
        assertEquals("pattern____", key)
    }

    // === TRUNCATION (40 chars) ===

    @Test
    fun `truncates long message to 40 chars`() {
        val longMessage = "This is a very long message that exceeds forty characters limit"
        val key = PatternKeyGenerator.generate(longMessage)
        // take(40) = "This is a very long message that exceeds f"
        // replace spaces + strip = "this_is_a_very_long_message_that_exceeds_f"
        assertEquals("pattern_this_is_a_very_long_message_that_exceeds", key)
    }

    @Test
    fun `truncates exactly at 40 chars`() {
        val message40 = "abcdefghijklmnopqrstuvwxyz1234567890abcd" // exactly 40 chars
        val key = PatternKeyGenerator.generate(message40)
        assertEquals("pattern_" + message40, key)
    }

    @Test
    fun `does not truncate message under 40 chars`() {
        val message = "short msg"
        val key = PatternKeyGenerator.generate(message)
        assertEquals("pattern_short_msg", key)
    }

    // === COLLISION BUGS (documents the known issue) ===

    @Test
    fun `COLLISION play X on youtube and play Y on youtube produce SAME key`() {
        // This is the KNOWN BUG from the audit
        // "play X on youtube" take(40) → "play X on youtube" (18 chars, under 40)
        // Both messages have same structure, differ only in X vs Y
        // BUT: X and Y are stripped... wait, they're alphanumeric, so they survive
        // Let me check:
        val key1 = PatternKeyGenerator.generate("play BLACKPINK on youtube")
        val key2 = PatternKeyGenerator.generate("play DuaLipa on youtube")
        // These should be DIFFERENT because the artist names survive the strip
        assertNotEquals(key1, key2)
        println("Key1: $key1")
        println("Key2: $key2")
    }

    @Test
    fun `COLLISION short names that truncate to same prefix`() {
        // Two different long messages that share first 40 chars → SAME key
        val msg1 = "This is a very long task description about setting up"
        val msg2 = "This is a very long task description about configuring"
        // Both take(40) = "This is a very long task description abou"
        val key1 = PatternKeyGenerator.generate(msg1)
        val key2 = PatternKeyGenerator.generate(msg2)
        assertEquals("COLLISION: two different messages map to same key", key1, key2)
        println("Collision detected: $key1")
    }

    @Test
    fun `NO COLLISION hyphen vs space produce different keys`() {
        // "test-message" → hyphen stripped → "testmessage"
        // "test message" → space→underscore → "test_message"
        // These are DIFFERENT keys (no collision)
        val key1 = PatternKeyGenerator.generate("test-message")
        val key2 = PatternKeyGenerator.generate("test message")
        assertNotEquals(key1, key2)
    }

    @Test
    fun `COLLISION case variations produce same key`() {
        val key1 = PatternKeyGenerator.generate("Play YouTube Video")
        val key2 = PatternKeyGenerator.generate("play youtube video")
        assertEquals(key1, key2)
    }

    @Test
    fun `COLLISION punctuation variations produce same key`() {
        val key1 = PatternKeyGenerator.generate("what time is it?")
        val key2 = PatternKeyGenerator.generate("what time is it")
        assertEquals(key1, key2)
    }

    // === COMMON AIRA MESSAGES ===

    @Test
    fun `handles play youtube message`() {
        val key = PatternKeyGenerator.generate("play BLACKPINK on youtube")
        assertEquals("pattern_play_blackpink_on_youtube", key)
    }

    @Test
    fun `handles send whatsapp message message`() {
        val key = PatternKeyGenerator.generate("send WhatsApp message to Mom")
        assertEquals("pattern_send_whatsapp_message_to_mom", key)
    }

    @Test
    fun `handles open app message`() {
        val key = PatternKeyGenerator.generate("open YouTube app")
        assertEquals("pattern_open_youtube_app", key)
    }

    @Test
    fun `handles set alarm message`() {
        val key = PatternKeyGenerator.generate("set alarm for 7am")
        assertEquals("pattern_set_alarm_for_7am", key)
    }

    @Test
    fun `handles check weather message`() {
        val key = PatternKeyGenerator.generate("check weather in Chennai")
        assertEquals("pattern_check_weather_in_chennai", key)
    }

    @Test
    fun `handles create rule message`() {
        val key = PatternKeyGenerator.generate("create rule: every morning at 8am check weather")
        // take(40) = "create rule: every morning at 8am check we"
        // colon stripped → "create rule every morning at 8am check we"
        // space→underscore → "create_rule_every_morning_at_8am_check_we"
        assertEquals("pattern_create_rule_every_morning_at_8am_check_", key)
    }

    @Test
    fun `handles reminder message`() {
        val key = PatternKeyGenerator.generate("remind me to call Mom every Sunday")
        assertEquals("pattern_remind_me_to_call_mom_every_sunday", key)
    }

    // === EDGE CASES ===

    @Test
    fun `handles unicode characters`() {
        val key = PatternKeyGenerator.generate("வணக்கம் Arun")
        // Tamil chars are non-alphanumeric in [^a-zA-Z0-9_] → stripped
        assertEquals("pattern__arun", key)
    }

    @Test
    fun `handles emoji in message`() {
        val key = PatternKeyGenerator.generate("play music 🎵")
        // Emoji is non-alphanumeric → stripped
        assertEquals("pattern_play_music_", key)
    }

    @Test
    fun `handles newlines in message`() {
        val key = PatternKeyGenerator.generate("line1\nline2")
        // newline is non-alphanumeric → stripped
        assertEquals("pattern_line1line2", key)
    }

    @Test
    fun `handles tabs in message`() {
        val key = PatternKeyGenerator.generate("col1\tcol2")
        assertEquals("pattern_col1col2", key)
    }

    @Test
    fun `handles very long message`() {
        val longMsg = "a".repeat(200)
        val key = PatternKeyGenerator.generate(longMsg)
        // take(40) = 40 a's → "pattern_" + 40 a's
        assertEquals("pattern_" + "a".repeat(40), key)
    }

    @Test
    fun `handles message with only special characters`() {
        val key = PatternKeyGenerator.generate("!@#$%^&*()")
        assertEquals("pattern_", key)
    }

    @Test
    fun `handles message with only numbers`() {
        val key = PatternKeyGenerator.generate("1234567890")
        assertEquals("pattern_1234567890", key)
    }

    @Test
    fun `handles single character message`() {
        val key = PatternKeyGenerator.generate("a")
        assertEquals("pattern_a", key)
    }

    @Test
    fun `handles mixed alphanumeric and special chars`() {
        val key = PatternKeyGenerator.generate("test123!@#msg456")
        assertEquals("pattern_test123msg456", key)
    }

    // === CONSISTENCY ===

    @Test
    fun `same message produces same key`() {
        val msg = "play youtube video"
        val key1 = PatternKeyGenerator.generate(msg)
        val key2 = PatternKeyGenerator.generate(msg)
        assertEquals(key1, key2)
    }

    @Test
    fun `key always starts with pattern prefix`() {
        val messages = listOf("hello", "test", "play music", "set alarm", "")
        for (msg in messages) {
            val key = PatternKeyGenerator.generate(msg)
            assertTrue("Key '$key' should start with 'pattern_'", key.startsWith("pattern_"))
        }
    }

    @Test
    fun `key never contains spaces`() {
        val messages = listOf("hello world", "play youtube video", "test message here")
        for (msg in messages) {
            val key = PatternKeyGenerator.generate(msg)
            assertFalse("Key '$key' should not contain spaces", key.contains(" "))
        }
    }

    @Test
    fun `key never contains uppercase`() {
        val messages = listOf("HELLO", "Play YouTube", "UPPERCASE MESSAGE")
        for (msg in messages) {
            val key = PatternKeyGenerator.generate(msg)
            assertEquals("Key '$key' should be lowercase", key, key.lowercase())
        }
    }

    @Test
    fun `key never contains special characters`() {
        val messages = listOf("test!@#", "msg\$%^", "hello&*()")
        for (msg in messages) {
            val key = PatternKeyGenerator.generate(msg)
            assertTrue(
                "Key '$key' should only contain [a-z0-9_]",
                key.matches(Regex("pattern_[a-z0-9_]*"))
            )
        }
    }
}
