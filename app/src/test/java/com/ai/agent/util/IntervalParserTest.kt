package com.ai.agent.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for IntervalParser — parses natural-language interval strings
 * like "every 2 hours", "30 minutes", "2h" into structured Interval objects.
 *
 * This was the source of 3 failures in the v4.0.97 log:
 *   1. triggerValue="star-slash-30"  → invalid time format (cron syntax, not recognized)
 *   2. triggerValue="30 minutes" → invalid interval format (missing "every" prefix)
 *   3. triggerValue="every 30 minutes" → SUCCESS (after 2 attempts)
 *
 * v4.0.98 fix: Made the parser more lenient (accepts without "every" prefix).
 * These tests verify the fix + catch edge cases.
 */
class IntervalParserTest {

    // === BASIC PARSE — WITH "every" PREFIX (original v4.0.96 format) ===

    @Test
    fun `parse every 2 hours`() {
        val result = IntervalParser.parse("every 2 hours")
        assertEquals(IntervalParser.Interval(2, IntervalParser.Unit.HOURS), result)
    }

    @Test
    fun `parse every 30 minutes`() {
        val result = IntervalParser.parse("every 30 minutes")
        assertEquals(IntervalParser.Interval(30, IntervalParser.Unit.MINUTES), result)
    }

    @Test
    fun `parse every 45 mins`() {
        val result = IntervalParser.parse("every 45 mins")
        assertEquals(IntervalParser.Interval(45, IntervalParser.Unit.MINUTES), result)
    }

    @Test
    fun `parse every 1 hour`() {
        val result = IntervalParser.parse("every 1 hour")
        assertEquals(IntervalParser.Interval(1, IntervalParser.Unit.HOURS), result)
    }

    // === BASIC PARSE — WITHOUT "every" PREFIX (v4.0.98 fix) ===

    @Test
    fun `parse 30 minutes without every prefix`() {
        val result = IntervalParser.parse("30 minutes")
        assertEquals(IntervalParser.Interval(30, IntervalParser.Unit.MINUTES), result)
    }

    @Test
    fun `parse 2 hours without every prefix`() {
        val result = IntervalParser.parse("2 hours")
        assertEquals(IntervalParser.Interval(2, IntervalParser.Unit.HOURS), result)
    }

    // === SHORT FORM (v4.0.98 fix) ===

    @Test
    fun `parse 2h short form`() {
        val result = IntervalParser.parse("2h")
        assertEquals(IntervalParser.Interval(2, IntervalParser.Unit.HOURS), result)
    }

    @Test
    fun `parse 30m short form`() {
        val result = IntervalParser.parse("30m")
        assertEquals(IntervalParser.Interval(30, IntervalParser.Unit.MINUTES), result)
    }

    @Test
    fun `parse every 2h short form with prefix`() {
        val result = IntervalParser.parse("every 2h")
        assertEquals(IntervalParser.Interval(2, IntervalParser.Unit.HOURS), result)
    }

    @Test
    fun `parse every 30m short form with prefix`() {
        val result = IntervalParser.parse("every 30m")
        assertEquals(IntervalParser.Interval(30, IntervalParser.Unit.MINUTES), result)
    }

    // === UNIT VARIATIONS ===

    @Test
    fun `parse hr as hours`() {
        val result = IntervalParser.parse("every 3 hr")
        assertEquals(IntervalParser.Interval(3, IntervalParser.Unit.HOURS), result)
    }

    @Test
    fun `parse min as minutes`() {
        val result = IntervalParser.parse("every 15 min")
        assertEquals(IntervalParser.Interval(15, IntervalParser.Unit.MINUTES), result)
    }

    // === CASE INSENSITIVITY ===

    @Test
    fun `parse is case insensitive`() {
        assertEquals(IntervalParser.Interval(2, IntervalParser.Unit.HOURS), IntervalParser.parse("EVERY 2 HOURS"))
        assertEquals(IntervalParser.Interval(30, IntervalParser.Unit.MINUTES), IntervalParser.parse("Every 30 Minutes"))
        assertEquals(IntervalParser.Interval(1, IntervalParser.Unit.HOURS), IntervalParser.parse("1 HOUR"))
    }

    // === NORMALIZE METHOD ===

    @Test
    fun `normalize every 2 hours to every 2h`() {
        assertEquals("every 2h", IntervalParser.normalize("every 2 hours"))
    }

    @Test
    fun `normalize 30 minutes to every 30m`() {
        assertEquals("every 30m", IntervalParser.normalize("30 minutes"))
    }

    @Test
    fun `normalize 2h stays every 2h`() {
        assertEquals("every 2h", IntervalParser.normalize("2h"))
    }

    // === TO MILLIS ===

    @Test
    fun `toMillis for 2 hours`() {
        val interval = IntervalParser.Interval(2, IntervalParser.Unit.HOURS)
        assertEquals(7200000L, interval.toMillis())
    }

    @Test
    fun `toMillis for 30 minutes`() {
        val interval = IntervalParser.Interval(30, IntervalParser.Unit.MINUTES)
        assertEquals(1800000L, interval.toMillis())
    }

    @Test
    fun `toMillis for 1 hour`() {
        val interval = IntervalParser.Interval(1, IntervalParser.Unit.HOURS)
        assertEquals(3600000L, interval.toMillis())
    }

    @Test
    fun `toMillis for 1 minute`() {
        val interval = IntervalParser.Interval(1, IntervalParser.Unit.MINUTES)
        assertEquals(60000L, interval.toMillis())
    }

    // === EDGE CASES — INVALID INPUTS ===

    @Test
    fun `parse empty string returns null`() {
        assertNull(IntervalParser.parse(""))
    }

    @Test
    fun `parse just every returns null`() {
        assertNull(IntervalParser.parse("every"))
    }

    @Test
    fun `parse just a number returns null`() {
        assertNull(IntervalParser.parse("30"))
    }

    @Test
    fun `parse just a unit returns null`() {
        assertNull(IntervalParser.parse("hours"))
    }

    @Test
    fun `parse zero hours returns null`() {
        assertNull(IntervalParser.parse("0 hours"))
    }

    @Test
    fun `parse zero minutes returns null`() {
        assertNull(IntervalParser.parse("0 minutes"))
    }

    @Test
    fun `parse cron syntax returns null`() {
        // This was the first failure in logs — AI used cron syntax "*/30"
        assertNull(IntervalParser.parse("*/30"))
    }

    @Test
    fun `parse garbage returns null`() {
        assertNull(IntervalParser.parse("hello world"))
    }

    @Test
    fun `parse null-adjacent returns null`() {
        assertNull(IntervalParser.parse("every 0 hours"))
    }

    // === EDGE CASES — TRICKY INPUTS ===

    @Test
    fun `parse with extra spaces`() {
        assertEquals(IntervalParser.Interval(2, IntervalParser.Unit.HOURS), IntervalParser.parse("every   2   hours"))
    }

    @Test
    fun `parse with no space between number and unit`() {
        assertEquals(IntervalParser.Interval(2, IntervalParser.Unit.HOURS), IntervalParser.parse("2hours"))
    }

    @Test
    fun `parse picks first match in compound string`() {
        // "2 hours 30 minutes" → picks "2 hours" (first match)
        val result = IntervalParser.parse("2 hours 30 minutes")
        assertEquals(IntervalParser.Interval(2, IntervalParser.Unit.HOURS), result)
    }

    // === REAL-WORLD LOG INPUTS (from v4.0.97 test logs) ===

    @Test
    fun `real-world input every 30 minutes succeeds`() {
        // This was the 3rd attempt in the logs that finally worked
        val result = IntervalParser.parse("every 30 minutes")
        assertEquals("every 30m", result?.normalized())
    }

    @Test
    fun `real-world input 30 minutes succeeds after v4 0 98 fix`() {
        // This was the 2nd attempt in the logs that FAILED before v4.0.98
        val result = IntervalParser.parse("30 minutes")
        assertEquals("every 30m", result?.normalized())
    }

    @Test
    fun `real-world input star slash 30 returns null`() {
        // This was the 1st attempt in the logs that FAILED — cron syntax
        assertNull(IntervalParser.parse("*/30"))
    }
}
