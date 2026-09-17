package com.jcversa.swiftslate.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jcversa.swiftslate.model.Command
import com.jcversa.swiftslate.model.CommandType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CommandManagerTest {
    private lateinit var commandManager: CommandManager

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // Clear prefs to ensure clean state
        context.getSharedPreferences("commands", 0).edit().clear().commit()
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        commandManager = CommandManager(context)
    }

    @Test
    fun corruptedMigrationPreferences_doNotPreventConstruction() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("commands", 0).edit()
            .putInt("aliases_reset_v1", 1)
            .putInt("custom_commands", 2)
            .commit()

        val repaired = CommandManager(context)

        assertNotNull(repaired.getCommands())
        val repairedJson = context.getSharedPreferences("commands", 0)
            .getString("custom_commands", null)
        assertNotNull(repairedJson)
        // getCommands may seed the built-in editable AI commands after repairing the
        // corrupted preference; the invariant is that the value is valid JSON, not empty.
        JSONArray(repairedJson)
    }

    // --- findCommand ---

    @Test
    fun findCommand_withFixTrigger_returnsFixCommand() {
        val result = commandManager.findCommand("hello world?fix")
        assertNotNull(result)
        assertEquals("?fix", result!!.trigger)
        assertFalse(result.isBuiltIn)
    }

    @Test
    fun findCommand_withImproveTrigger_returnsImproveCommand() {
        val result = commandManager.findCommand("some text?improve")
        assertNotNull(result)
        assertEquals("?improve", result!!.trigger)
    }

    @Test
    fun findCommand_withUndoTrigger_returnsUndoCommand() {
        val result = commandManager.findCommand("text?undo")
        assertNotNull(result)
        assertEquals("?undo", result!!.trigger)
    }

    @Test
    fun findCommand_noTrigger_returnsNull() {
        assertNull(commandManager.findCommand("just some plain text"))
    }

    @Test
    fun findCommand_emptyText_returnsNull() {
        assertNull(commandManager.findCommand(""))
    }

    @Test
    fun findCommand_translateWithValidLangCode_returnsTranslateCommand() {
        val result = commandManager.findCommand("hello?translate:es")
        assertNotNull(result)
        assertEquals("?translate:es", result!!.trigger)
        assertTrue(result.prompt.contains("es"))
        assertTrue(result.isBuiltIn)
    }

    @Test
    fun findCommand_translateWithOneCharCode_returnsNull() {
        assertNull(commandManager.findCommand("hello?translate:x"))
    }

    @Test
    fun findCommand_translateWithSixCharCode_returnsNull() {
        assertNull(commandManager.findCommand("hello?translate:abcdef"))
    }

    @Test
    fun findCommand_longestMatchWins() {
        commandManager.saveCustomCommand(Command("?fix2", "Custom fix2 prompt"))
        val result = commandManager.findCommand("text?fix2")
        assertNotNull(result)
        assertEquals("?fix2", result!!.trigger)
        assertFalse(result.isBuiltIn)
    }

    // --- getCommands ---

    @Test
    fun getCommands_returnsTwentyTwoCommandsByDefault() {
        val commands = commandManager.getCommands()
        assertEquals(22, commands.size)
    }

    @Test
    fun getCommands_systemCommandsHaveIsBuiltInTrue() {
        val commands = commandManager.getCommands()
        val systemTriggers = listOf("?undo", "?copy", "?cut", "?paste", "?replace", "?translate:xx")
        val systemCommands = commands.filter { it.trigger in systemTriggers }
        assertEquals(6, systemCommands.size)
        assertTrue(systemCommands.all { it.isBuiltIn })
    }

    @Test
    fun getCommands_aiCommandsHaveIsBuiltInFalse() {
        val commands = commandManager.getCommands()
        val aiTriggers = listOf("?fix", "?improve", "?shorten", "?expand", "?formal", "?casual", "?emoji", "?human", "?reply")
        val aiCommands = commands.filter { it.trigger in aiTriggers }
        assertEquals(9, aiCommands.size)
        assertTrue(aiCommands.all { !it.isBuiltIn })
    }

    @Test
    fun getCommands_styleCommandsAreBuiltInLocalCommands() {
        val commands = commandManager.getCommands()
        val styleTriggers = listOf("?bold", "?italic", "?mono", "?bubble", "?gothic", "?smallcaps", "?normal")
        val styleCommands = commands.filter { it.trigger in styleTriggers }
        assertEquals(7, styleCommands.size)
        assertTrue(styleCommands.all {
            it.isBuiltIn && it.type == CommandType.TEXT_REPLACER && TextStyleTransformer.isStyleCommand(it)
        })
    }

    @Test
    fun findCommand_styleTriggerReturnsLocalStyleCommand() {
        val result = commandManager.findCommand("hello?bold")
        assertNotNull(result)
        assertEquals("?bold", result!!.trigger)
        assertEquals(CommandType.TEXT_REPLACER, result.type)
        assertTrue(TextStyleTransformer.isStyleCommand(result))
    }

    @Test
    fun saveCustomCommand_rejectsCollisionWithStyleCommand() {
        assertFalse(commandManager.saveCustomCommand(Command("?bold", "replace with something else")))
        assertFalse(commandManager.saveCustomCommand(Command("?boldface", "shadow style command")))
    }

    @Test
    fun getCommands_afterAddingCustom_includesIt() {
        commandManager.saveCustomCommand(Command("?myCmd", "do something"))
        val commands = commandManager.getCommands()
        assertEquals(23, commands.size)
        assertTrue(commands.any { it.trigger == "?myCmd" })
    }

    @Test
    fun getCommands_builtInsUseCurrentPrefix() {
        commandManager.setTriggerPrefix("!")
        val commands = commandManager.getCommands()
        assertTrue(commands.filter { it.isBuiltIn }.all { it.trigger.startsWith("!") })
    }

    // --- saveCustomCommand / removeCustomCommand ---

    @Test
    fun saveCustomCommand_makesFindable() {
        commandManager.saveCustomCommand(Command("?greet", "Say hello"))
        val result = commandManager.findCommand("hi?greet")
        assertNotNull(result)
        assertEquals("?greet", result!!.trigger)
    }

    @Test
    fun saveCustomCommand_aliasIsPersistedAndFindable() {
        assertTrue(commandManager.saveCustomCommand(
            Command("?greet", "Say hello", aliases = listOf("?hello", "?salut"))
        ))
        val result = commandManager.findCommand("hi?hello")
        assertNotNull(result)
        assertEquals("?greet", result!!.trigger)
        assertEquals(listOf("?hello", "?salut"), result.aliases)
    }

    @Test
    fun findCommandMatch_aliasReportsTheMatchedAlias() {
        assertTrue(commandManager.saveCustomCommand(
            Command("?greet", "Say hello", aliases = listOf("?hello"))
        ))
        val match = commandManager.findCommandMatch("hi?hello")
        assertNotNull(match)
        assertEquals("?greet", match!!.command.trigger)
        assertEquals("?hello", match.matchedTrigger)
    }

    @Test
    fun removeCustomCommand_makesUnfindable() {
        commandManager.saveCustomCommand(Command("?greet", "Say hello"))
        commandManager.removeCustomCommand("?greet")
        assertNull(commandManager.findCommand("hi?greet"))
    }

    @Test
    fun removeCustomCommand_nonExistentTrigger_doesNotCrash() {
        commandManager.removeCustomCommand("?nonexistent")
    }

    // --- getTriggerPrefix / setTriggerPrefix ---

    @Test
    fun getTriggerPrefix_defaultIsQuestionMark() {
        assertEquals("?", commandManager.getTriggerPrefix())
    }

    @Test
    fun setTriggerPrefix_validSymbol_returnsTrue() {
        assertTrue(commandManager.setTriggerPrefix("!"))
        assertEquals("!", commandManager.getTriggerPrefix())
    }

    @Test
    fun setTriggerPrefix_letter_returnsFalse() {
        assertFalse(commandManager.setTriggerPrefix("a"))
    }

    @Test
    fun setTriggerPrefix_digit_returnsFalse() {
        assertFalse(commandManager.setTriggerPrefix("1"))
    }

    @Test
    fun setTriggerPrefix_whitespace_returnsFalse() {
        assertFalse(commandManager.setTriggerPrefix(" "))
    }

    @Test
    fun setTriggerPrefix_multiChar_returnsFalse() {
        assertFalse(commandManager.setTriggerPrefix("!!"))
    }

    @Test
    fun setTriggerPrefix_builtInsUseNewPrefix() {
        commandManager.setTriggerPrefix("!")
        val commands = commandManager.getCommands()
        assertTrue(commands.filter { it.isBuiltIn }.all { it.trigger.startsWith("!") })
    }

    @Test
    fun setTriggerPrefix_customCommandsMigrated() {
        commandManager.saveCustomCommand(Command("?myCmd", "do something"))
        commandManager.setTriggerPrefix("!")
        val commands = commandManager.getCommands()
        assertTrue(commands.any { it.trigger == "!myCmd" })
        assertFalse(commands.any { it.trigger == "?myCmd" })
    }

    @Test
    fun setTriggerPrefix_migratesAliasesToo() {
        commandManager.saveCustomCommand(Command("?myCmd", "do something", aliases = listOf("?alias")))
        commandManager.setTriggerPrefix("!")
        val command = commandManager.getCommands().first { it.trigger == "!myCmd" }
        assertEquals(listOf("!alias"), command.aliases)
        assertEquals("!myCmd", commandManager.findCommand("text!alias")!!.trigger)
    }


    // --- write-path validation (shared by saveCustomCommand and importCommands) ---

    @Test
    fun saveCustomCommand_rejectsTriggerWithoutPrefix() {
        assertFalse(commandManager.saveCustomCommand(Command("noprefix", "do a thing")))
        assertNull(commandManager.findCommand("hello noprefix"))
    }

    @Test
    fun saveCustomCommand_rejectsPrefixOnlyTrigger() {
        assertFalse(commandManager.saveCustomCommand(Command("?", "do a thing")))
    }

    @Test
    fun saveCustomCommand_rejectsBlankPrompt() {
        assertFalse(commandManager.saveCustomCommand(Command("?thing", "   ")))
    }

    @Test
    fun saveCustomCommand_rejectsOverlongTriggerAndPrompt() {
        val longTrigger = "?" + "a".repeat(CommandManager.MAX_TRIGGER_LENGTH)
        assertFalse(commandManager.saveCustomCommand(Command(longTrigger, "p")))
        val longPrompt = "a".repeat(CommandManager.MAX_PROMPT_LENGTH + 1)
        assertFalse(commandManager.saveCustomCommand(Command("?thing", longPrompt)))
    }

    @Test
    fun saveCustomCommand_acceptsValidCommandAtTheLimits() {
        val maxTrigger = "?" + "a".repeat(CommandManager.MAX_TRIGGER_LENGTH - 1)
        assertTrue(commandManager.saveCustomCommand(Command(maxTrigger, "a".repeat(CommandManager.MAX_PROMPT_LENGTH))))
        assertNotNull(commandManager.findCommand("hello $maxTrigger"))
    }

    /** Import is strictly more lenient than save: it sanitizes what the UI would reject. */
    @Test
    fun importCommands_sanitizesWhatSaveRejects() {
        val overLongPrompt = "a".repeat(CommandManager.MAX_PROMPT_LENGTH + 10)
        val overLongTrigger = "?" + "a".repeat(CommandManager.MAX_TRIGGER_LENGTH + 5)
        val json = JSONArray()
            .put(JSONObject().put("trigger", overLongTrigger).put("prompt", overLongPrompt).put("type", "AI"))
            .put(JSONObject().put("trigger", "noprefix").put("prompt", "p").put("type", "AI"))
            .toString()
        assertTrue(commandManager.importCommands(json))
        val stored = JSONArray(commandManager.exportCommands())
        assertEquals(2, stored.length())
        val first = stored.getJSONObject(0)
        assertEquals(CommandManager.MAX_TRIGGER_LENGTH, first.getString("trigger").length)
        assertEquals(CommandManager.MAX_PROMPT_LENGTH, first.getString("prompt").length)
        val migrated = stored.getJSONObject(1)
        assertEquals("?noprefix", migrated.getString("trigger"))
    }

    @Test
    fun importCommands_dropsInvalidEntriesButKeepsValidOnes() {
        val json = JSONArray()
            .put(JSONObject().put("trigger", "   ").put("prompt", "p").put("type", "AI"))
            .put(JSONObject().put("trigger", "?ok").put("prompt", "  ").put("type", "AI"))
            .put(JSONObject().put("trigger", "?").put("prompt", "p").put("type", "AI"))
            .put(JSONObject().put("trigger", "?good").put("prompt", "keep me").put("type", "AI"))
            .toString()
        assertTrue(commandManager.importCommands(json))
        val stored = JSONArray(commandManager.exportCommands())
        assertEquals(1, stored.length())
        assertEquals("?good", stored.getJSONObject(0).getString("trigger"))
    }

    @Test
    fun importCommands_emptyArray_isValidNoop() {
        assertTrue(commandManager.importCommands("[]"))
        assertEquals("[]", commandManager.exportCommands())
    }

    @Test
    fun importCommands_keepsOnlyUpToTheMaximum() {
        val arr = JSONArray()
        for (i in 0 until (CommandManager.MAX_CUSTOM_COMMANDS + 5)) {
            val name = i.toString().padStart(3, '0')
            arr.put(JSONObject().put("trigger", "?c$name").put("prompt", "p").put("type", "AI"))
        }
        assertTrue(commandManager.importCommands(arr.toString()))
        val stored = JSONArray(commandManager.exportCommands())
        assertEquals(CommandManager.MAX_CUSTOM_COMMANDS, stored.length())
    }

    @Test
    fun importCommands_unknownTypeDefaultsToAi() {
        val json = JSONArray().put(
            JSONObject().put("trigger", "?ok").put("prompt", "p").put("type", "SOMETHING_ELSE")
        ).toString()
        assertTrue(commandManager.importCommands(json))
        val stored = JSONArray(commandManager.exportCommands())
        assertEquals(CommandType.AI.name, stored.getJSONObject(0).getString("type"))
    }

    @Test
    fun importCommands_keepsTextReplacerType() {
        val json = JSONArray().put(
            JSONObject().put("trigger", "?sig").put("prompt", "regards").put("type", "TEXT_REPLACER")
        ).toString()
        assertTrue(commandManager.importCommands(json))
        val stored = JSONArray(commandManager.exportCommands())
        assertEquals(CommandType.TEXT_REPLACER.name, stored.getJSONObject(0).getString("type"))
    }

    @Test
    fun importCommands_rejectsMalformedJson() {
        assertFalse(commandManager.importCommands("not json at all"))
    }

    @Test
    fun importCommands_rejectsJsonWithNoUsableEntries() {
        val json = JSONArray()
            .put(JSONObject().put("trigger", "   ").put("prompt", "p").put("type", "AI"))
            .put(JSONObject().put("trigger", "?ok").put("prompt", "").put("type", "AI"))
            .toString()
        assertFalse(commandManager.importCommands(json))
    }

    // --- updateCustomCommand ---

    @Test
    fun saveCustomCommand_renamesInASingleWrite() {
        assertTrue(commandManager.saveCustomCommand(Command("?old", "original")))
        assertTrue(commandManager.saveCustomCommand(Command("?new", "changed"), replacing = "?old"))
        assertNull(commandManager.findCommand("hello ?old"))
        val found = commandManager.findCommand("hello ?new")
        assertNotNull(found)
        assertEquals("changed", found!!.prompt)
    }

    @Test
    fun saveCustomCommand_editingInPlaceDoesNotDuplicate() {
        assertTrue(commandManager.saveCustomCommand(Command("?same", "v1")))
        assertTrue(commandManager.saveCustomCommand(Command("?same", "v2")))
        assertEquals(1, commandManager.getCommands().count { it.trigger == "?same" })
        assertEquals("v2", commandManager.findCommand("x ?same")!!.prompt)
    }

    /** A rejected save must leave the existing command untouched rather than deleting it. */
    @Test
    fun saveCustomCommand_invalidReplacementKeepsOriginal() {
        assertTrue(commandManager.saveCustomCommand(Command("?keep", "original")))
        assertFalse(commandManager.saveCustomCommand(Command("bad", "x"), replacing = "?keep"))
        assertEquals("original", commandManager.findCommand("y ?keep")!!.prompt)
    }

    @Test
    fun saveCustomCommand_rejectsCollisionWithBuiltInOrExistingAlias() {
        assertFalse(commandManager.saveCustomCommand(Command("?copycat", "shadow built-in")))
        assertTrue(commandManager.saveCustomCommand(Command("?greet", "say hello", aliases = listOf("?hello"))))
        assertFalse(commandManager.saveCustomCommand(Command("?other", "conflicting", aliases = listOf("?helloworld"))))
        assertFalse(commandManager.saveCustomCommand(Command("?helloagain", "conflicting")))
    }

    @Test
    fun saveCustomCommand_rejectsDynamicTranslateCollision() {
        assertFalse(commandManager.saveCustomCommand(Command("?translate", "shadow translation")))
        assertFalse(commandManager.saveCustomCommand(Command("?translate:es", "shadow translation")))
    }

    @Test
    fun importCommands_dropsGlobalCollisions() {
        val json = JSONArray()
            .put(JSONObject().put("trigger", "?first").put("prompt", "one").put("aliases", JSONArray().put("?shared")))
            .put(JSONObject().put("trigger", "?shared").put("prompt", "two"))
            .put(JSONObject().put("trigger", "?copycat").put("prompt", "three"))
        assertTrue(commandManager.importCommands(json.toString()))
        val stored = JSONArray(commandManager.exportCommands())
        assertEquals(1, stored.length())
        assertEquals("?first", stored.getJSONObject(0).getString("trigger"))
    }

    // --- cache invalidation (the prefix is part of the cache key) ---

    @Test
    fun changingPrefixWithNoCustomCommands_stillUpdatesBuiltIns() {
        assertNotNull(commandManager.findCommand("hello ?copy"))
        commandManager.getCommands() // populate cache
        assertTrue(commandManager.setTriggerPrefix("/"))
        assertNotNull(commandManager.findCommand("hello /copy"))
        assertNull(commandManager.findCommand("hello ?copy"))
    }
}
