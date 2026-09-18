package com.jcversa.swiftslate.manager

import com.jcversa.swiftslate.model.Command
import com.jcversa.swiftslate.model.CommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextStyleTransformerTest {

    @Test
    fun boldTransformsAsciiLettersAndDigits() {
        assertEquals("𝗛𝗲𝗹𝗹𝗼 𝟮𝟬𝟮𝟲!", TextStyleTransformer.transform(TextStyle.BOLD, "Hello 2026!"))
    }

    @Test
    fun stylesPreserveWhitespacePunctuationAccentsAndEmoji() {
        val input = "Café déjà vu! 🎉\n你好"
        val result = TextStyleTransformer.transform(TextStyle.ITALIC, input)

        assertEquals("𝘊𝘢𝘧é 𝘥é𝘫à 𝘷𝘶! 🎉\n你好", result)
    }

    @Test
    fun bubbleTransformsLettersAndDigits() {
        assertEquals("ⓈⓦⓘⓕⓣⓈⓛⓐⓣⓔ ①②③", TextStyleTransformer.transform(TextStyle.BUBBLE, "SwiftSlate 123"))
    }

    @Test
    fun smallCapsLeavesUnsupportedCharactersUntouched() {
        assertEquals("ꜱᴡɪꜰᴛꜱʟᴀᴛᴇ 123!", TextStyleTransformer.transform(TextStyle.SMALL_CAPS, "SwiftSlate 123!"))
    }

    @Test
    fun normalReversesSupportedStylesWithoutChangingOrdinaryText() {
        val styled = TextStyleTransformer.transform(TextStyle.GOTHIC, "SwiftSlate 42")
        assertEquals("SwiftSlate 42", TextStyleTransformer.transform(TextStyle.NORMAL, styled))
        assertEquals("Plain text 42", TextStyleTransformer.transform(TextStyle.NORMAL, "Plain text 42"))
    }

    @Test
    fun styleForOnlyRecognizesBuiltInLocalStyleCommands() {
        val builtIn = Command("?bold", "description", isBuiltIn = true, type = CommandType.TEXT_REPLACER)
        val custom = Command("?bold", "description", isBuiltIn = false, type = CommandType.TEXT_REPLACER)
        val ai = Command("?bold", "description", isBuiltIn = true, type = CommandType.AI)

        assertEquals(TextStyle.BOLD, TextStyleTransformer.styleFor(builtIn))
        assertNull(TextStyleTransformer.styleFor(custom))
        assertNull(TextStyleTransformer.styleFor(ai))
        assertTrue(TextStyleTransformer.isStyleCommand(builtIn))
        assertFalse(TextStyleTransformer.isStyleCommand(custom))
    }
}
