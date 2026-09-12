package com.jcversa.swiftslate.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure unit tests for the input-size and temperature guards. The network path is not exercised. */
class CommandRunnerTest {

    @Test
    fun isInputTooLarge_acceptsOrdinarySelections() {
        assertFalse(isInputTooLarge("Hello, how are you?"))
        assertFalse(isInputTooLarge("a".repeat(MAX_INPUT_CHARS)))
    }

    @Test
    fun isInputTooLarge_rejectsAbsurdSizes() {
        assertTrue(isInputTooLarge("a".repeat(MAX_INPUT_CHARS + 1)))
    }

    @Test
    fun sanitizeTemperature_passesThroughInRangeValues() {
        assertEquals(0.0, sanitizeTemperature(0f), 0.0)
        assertEquals(0.5, sanitizeTemperature(0.5f), 0.0)
        assertEquals(2.0, sanitizeTemperature(2f), 0.0)
    }

    @Test
    fun sanitizeTemperature_clampsOutOfRangeValues() {
        assertEquals(MIN_TEMPERATURE, sanitizeTemperature(-1f), 0.0)
        assertEquals(MAX_TEMPERATURE, sanitizeTemperature(99f), 0.0)
        assertEquals(MAX_TEMPERATURE, sanitizeTemperature(Float.POSITIVE_INFINITY), 0.0)
        assertEquals(MIN_TEMPERATURE, sanitizeTemperature(Float.NEGATIVE_INFINITY), 0.0)
    }

    /** NaN would sail through coerceIn (all comparisons false), so it needs its own branch. */
    @Test
    fun sanitizeTemperature_fallsBackToDefaultOnNaN() {
        assertEquals(0.5, sanitizeTemperature(Float.NaN), 0.0)
    }
}
