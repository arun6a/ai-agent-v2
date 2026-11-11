package com.ai.agent.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for RuleValidator — validates rule trigger types, time formats, and day names.
 */
class RuleValidatorTest {

    // === TIME FORMAT VALIDATION ===

    @Test
    fun `valid time 07 00`() {
        assertTrue(RuleValidator.isValidTimeFormat("07:00"))
    }

    @Test
    fun `valid time 23 59`() {
        assertTrue(RuleValidator.isValidTimeFormat("23:59"))
    }

    @Test
    fun `valid time 00 00 midnight`() {
        assertTrue(RuleValidator.isValidTimeFormat("00:00"))
    }

    @Test
    fun `valid time 12 30 noon`() {
        assertTrue(RuleValidator.isValidTimeFormat("12:30"))
    }

    @Test
    fun `invalid time 24 00`() {
        assertFalse(RuleValidator.isValidTimeFormat("24:00"))
    }

    @Test
    fun `invalid time 23 60`() {
        assertFalse(RuleValidator.isValidTimeFormat("23:60"))
    }

    @Test
    fun `valid time single digit hour`() {
        // "7:00" is a valid time (7 AM) — we accept 1-digit hours
        assertTrue(RuleValidator.isValidTimeFormat("7:00"))
    }

    @Test
    fun `invalid time no colon`() {
        assertFalse(RuleValidator.isValidTimeFormat("0700"))
    }

    @Test
    fun `invalid time empty`() {
        assertFalse(RuleValidator.isValidTimeFormat(""))
    }

    @Test
    fun `invalid time garbage`() {
        assertFalse(RuleValidator.isValidTimeFormat("abc"))
    }

    @Test
    fun `invalid time null-ish`() {
        assertFalse(RuleValidator.isValidTimeFormat(":"))
    }

    @Test
    fun `invalid time with seconds`() {
        // HH:MM:SS is not supported — only HH:MM
        assertFalse(RuleValidator.isValidTimeFormat("07:00:00"))
    }

    // === DAY VALIDATION ===

    @Test
    fun `valid single day mon`() {
        assertNull(RuleValidator.validateDays("mon"))
    }

    @Test
    fun `valid multiple days`() {
        assertNull(RuleValidator.validateDays("mon,tue,wed,thu,fri"))
    }

    @Test
    fun `valid all days`() {
        assertNull(RuleValidator.validateDays("mon,tue,wed,thu,fri,sat,sun"))
    }

    @Test
    fun `valid null days`() {
        assertNull(RuleValidator.validateDays(null))
    }

    @Test
    fun `valid empty days`() {
        assertNull(RuleValidator.validateDays(""))
    }

    @Test
    fun `valid days with spaces`() {
        assertNull(RuleValidator.validateDays("mon, tue, wed"))
    }

    @Test
    fun `valid days uppercase`() {
        assertNull(RuleValidator.validateDays("MON,TUE,WED"))
    }

    @Test
    fun `invalid day full name`() {
        // "monday" is not valid — must be 3-letter "mon"
        val result = RuleValidator.validateDays("monday")
        assertTrue(result != null)
        assertTrue(result!!.contains("invalid"))
    }

    @Test
    fun `invalid day number`() {
        val result = RuleValidator.validateDays("1,2,3")
        assertTrue(result != null)
    }

    @Test
    fun `invalid mixed valid and invalid days`() {
        val result = RuleValidator.validateDays("mon,invalid,tue")
        assertTrue(result != null)
        assertTrue(result!!.contains("invalid"))
    }

    // === TRIGGER TYPE VALIDATION ===

    @Test
    fun `valid trigger type time`() {
        assertTrue(RuleValidator.validateTriggerType("time"))
    }

    @Test
    fun `valid trigger type interval`() {
        assertTrue(RuleValidator.validateTriggerType("interval"))
    }

    @Test
    fun `valid trigger type battery_low`() {
        assertTrue(RuleValidator.validateTriggerType("battery_low"))
    }

    @Test
    fun `valid trigger type notification`() {
        assertTrue(RuleValidator.validateTriggerType("notification"))
    }

    @Test
    fun `invalid trigger type unknown`() {
        assertFalse(RuleValidator.validateTriggerType("unknown"))
    }

    @Test
    fun `invalid trigger type empty`() {
        assertFalse(RuleValidator.validateTriggerType(""))
    }

    @Test
    fun `invalid trigger type capital`() {
        // Trigger types are case-sensitive
        assertFalse(RuleValidator.validateTriggerType("Time"))
    }
}
