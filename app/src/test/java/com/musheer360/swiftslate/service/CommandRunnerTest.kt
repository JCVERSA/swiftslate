package com.musheer360.swiftslate.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure unit tests for the input-size guard. The network path is not exercised. */
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
}
