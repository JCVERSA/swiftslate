package com.jcversa.swiftslate.manager

import android.content.Context
import android.content.SharedPreferences
import com.jcversa.swiftslate.model.Command
import com.jcversa.swiftslate.model.CommandType
import com.jcversa.swiftslate.model.CommandMatch
import org.json.JSONArray
import org.json.JSONObject

class CommandManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("commands", Context.MODE_PRIVATE)
    private val settingsPrefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Volatile
    private var cachedCommands: List<Command>? = null
    @Volatile
    private var cacheTimestamp = 0L
    /**
     * Raw JSON [cachedCommands] was parsed from, so an expired TTL can be revalidated with a
     * string compare instead of re-parsing every command. The TTL has to stay: the UI and the
     * accessibility service hold separate CommandManager instances in one process, and this is
     * how the service notices commands edited in the UI. But it fired on the service's
     * keystroke path, so the parse ran again every 5s of typing for no change.
     */
    @Volatile
    private var cachedCommandsJson: String? = null
    /**
     * Prefix [cachedCommands] was built with. Part of the cache key because built-in triggers
     * are derived from it — with a JSON-only check, changing the prefix while no custom commands
     * exist leaves custom_commands as "[]" and the other instance would keep serving built-ins
     * under the old prefix forever.
     */
    @Volatile
    private var cachedPrefix: String? = null
    // Typed prefs reads can throw ClassCastException on a corrupted store; this runs on the
    // accessibility service's bind path, where an escape would kill the whole process (#125).
    private var aiCommandsSeeded =
        try { prefs.getBoolean("ai_commands_seeded", false) } catch (_: Exception) { false }

    init {
        // Apply the one-time clean slate before the UI or service can create a new alias.
        resetAliasesOnce()
    }

    companion object {
        const val DEFAULT_PREFIX = "?"
        const val PREF_TRIGGER_PREFIX = "trigger_prefix"
        private const val PREF_ALIASES_RESET_V1 = "aliases_reset_v1"
        private const val CACHE_TTL_MS = 5_000L

        /** Limits enforced on every write path — see [isValidCommand] / [importCommands]. */
        const val MAX_TRIGGER_LENGTH = 50
        const val MAX_PROMPT_LENGTH = 5_000
        const val MAX_ALIAS_LENGTH = 50
        const val MAX_ALIASES = 5
        const val MAX_CUSTOM_COMMANDS = 100

        /**
         * Whether a custom command is storable. Applied by both [saveCustomCommand] and
         * [importCommands]: the limits used to live only in the import path, so the UI could
         * create commands that the app's own exported backup would then refuse to import.
         */
        fun isValidCommand(trigger: String, prompt: String, prefix: String): Boolean =
            trigger.isNotBlank() && prompt.isNotBlank() &&
                trigger.length <= MAX_TRIGGER_LENGTH && prompt.length <= MAX_PROMPT_LENGTH &&
                trigger.startsWith(prefix) && trigger.length > prefix.length

        fun isValidAlias(alias: String, trigger: String, prefix: String): Boolean =
            alias.isNotBlank() && alias != trigger &&
                alias.length <= MAX_ALIAS_LENGTH &&
                alias.startsWith(prefix) && alias.length > prefix.length

        fun areValidAliases(aliases: List<String>, trigger: String, prefix: String): Boolean {
            if (aliases.size > MAX_ALIASES || aliases.distinct().size != aliases.size) return false
            if (aliases.any { !isValidAlias(it, trigger, prefix) }) return false

            // A trigger is also a prefix in the matching grammar. Treating `?foo` and
            // `?foobar` as two independent names would make the result depend on which
            // command happened to be iterated first. Validate the complete command locally,
            // not just exact duplicates.
            val names = listOf(trigger) + aliases
            return names.indices.all { index ->
                ((index + 1) until names.size).none { other ->
                    isTriggerConflict(names[index], names[other])
                }
            }
        }

        /** Two names collide when either can be a prefix of the other in [findCommandMatch]. */
        private fun isTriggerConflict(first: String, second: String): Boolean =
            first == second || first.startsWith(second) || second.startsWith(first)
    }

    // System commands — local operations that cannot be edited or deleted
    private val systemDefinitions = listOf(
        "undo" to "Undo the last replacement and restore the original text.",
        "copy" to "Copy the text to clipboard.",
        "cut" to "Cut the text to clipboard.",
        "paste" to "Paste from clipboard.",
        "replace" to "Replace text with clipboard content.",
        "translate:xx" to "Translate text to any language code (e.g. ?translate:es, ?translate:fr)."
    )

    // Default AI commands — seeded into custom commands on first run so users can edit/delete them
    private val defaultAiDefinitions = listOf(
        "fix" to "Fix grammar, spelling, and punctuation errors.",
        "improve" to "Rewrite to improve clarity, flow, and coherence.",
        "shorten" to "Rewrite to be more concise while preserving the core meaning.",
        "expand" to "Rewrite with more detail. Elaborate only on what is stated or widely known \u2014 do not fabricate information.",
        "formal" to "Rewrite in a formal, professional tone.",
        "casual" to "Rewrite in a casual, friendly tone.",
        "emoji" to "Add relevant emojis throughout.",
        "human" to "Rewrite to sound naturally human, not AI-generated. Never use emdashes or semicolons, use commas or periods instead. Drop AI clichés and filler phrases. Use contractions, everyday words, and varied sentence lengths. Keep all facts, names, and numbers intact.",
        "reply" to "Generate a contextual reply to this message."
    )

    /**
     * Built-ins include the dynamic translate command. A custom name beginning with
     * `prefix + translate` would otherwise shadow every language variant accepted below.
     */
    private fun conflictsWithBuiltIn(name: String, prefix: String): Boolean {
        val builtIns = systemDefinitions.map { (trigger, _) -> prefix + trigger }
        return builtIns.any { isTriggerConflict(name, it) } ||
            isTriggerConflict(name, prefix + "translate")
    }

    private fun conflictsWithExisting(name: String, existingNames: Collection<String>, prefix: String): Boolean =
        conflictsWithBuiltIn(name, prefix) || existingNames.any { isTriggerConflict(name, it) }

    private fun commandNames(command: Command): List<String> =
        listOf(command.trigger) + command.aliases

    /** Drops the cache and its validity key so the next [getCommands] rebuilds from prefs. */
    private fun invalidateCache() {
        cachedCommands = null
        cachedCommandsJson = null
        cachedPrefix = null
    }

    fun getTriggerPrefix(): String {
        return try {
            settingsPrefs.getString(PREF_TRIGGER_PREFIX, DEFAULT_PREFIX) ?: DEFAULT_PREFIX
        } catch (_: Exception) {
            DEFAULT_PREFIX
        }
    }

    private fun migrateTrigger(value: String, newPrefix: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith(newPrefix)) return trimmed.take(MAX_ALIAS_LENGTH)
        val stripped = if (trimmed.isNotEmpty() && !trimmed.first().isLetterOrDigit()) {
            trimmed.substring(1)
        } else {
            trimmed
        }
        return (newPrefix + stripped).take(MAX_ALIAS_LENGTH)
    }

    private fun readAliases(obj: JSONObject): List<String> {
        val arr = obj.optJSONArray("aliases") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { index ->
            arr.optString(index, "").trim().takeIf { it.isNotBlank() }
        }
    }

    private fun putAliases(obj: JSONObject, aliases: List<String>) {
        if (aliases.isNotEmpty()) obj.put("aliases", JSONArray(aliases))
    }

    @Synchronized fun setTriggerPrefix(newPrefix: String): Boolean {
        if (newPrefix.length != 1 || newPrefix[0].isLetterOrDigit() || newPrefix[0].isWhitespace()) return false
        // Write prefix first so crash between writes is self-healing on retry
        settingsPrefs.edit().putString(PREF_TRIGGER_PREFIX, newPrefix).apply()
        // Migrate custom command triggers — idempotent: always fix commands not matching current prefix
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) {
            prefs.edit().putString("custom_commands", "[]").apply()
            invalidateCache()
            return true
        }
        val newArr = JSONArray()
        val acceptedNames = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val oldTrigger = obj.optString("trigger", "")
            val prompt = obj.optString("prompt", "").trim().take(MAX_PROMPT_LENGTH)
            if (oldTrigger.isEmpty() || prompt.isEmpty()) continue
            val migrated = migrateTrigger(oldTrigger, newPrefix)
            if (!isValidCommand(migrated, prompt, newPrefix) ||
                conflictsWithExisting(migrated, acceptedNames, newPrefix)
            ) continue

            val aliases = readAliases(obj)
                .map { migrateTrigger(it, newPrefix) }
                .filter { alias ->
                    isValidAlias(alias, migrated, newPrefix) &&
                        !conflictsWithExisting(alias, acceptedNames + migrated, newPrefix)
                }
                .distinct()
                .take(MAX_ALIASES)
                .let { candidate ->
                    candidate.filter { alias ->
                        val names = listOf(migrated) + candidate.takeWhile { it != alias }
                        names.none { isTriggerConflict(alias, it) }
                    }
                }
            val newObj = JSONObject()
            newObj.put("trigger", migrated)
            newObj.put("prompt", prompt)
            newObj.put("type", obj.optString("type", CommandType.AI.name))
            putAliases(newObj, aliases)
            newArr.put(newObj)
            acceptedNames += migrated
            acceptedNames += aliases
        }
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        invalidateCache()
        return true
    }

    private fun getBuiltInCommands(): List<Command> {
        val prefix = getTriggerPrefix()
        return systemDefinitions.map { (name, prompt) -> Command("$prefix$name", prompt, true) }
    }

    private fun seedDefaultAiCommands() {
        val prefix = getTriggerPrefix()
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) { JSONArray() }
        val existingNames = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val trigger = obj.optString("trigger", "")
            if (trigger.isNotEmpty()) existingNames += trigger
            existingNames += readAliases(obj)
        }
        var added = false
        for ((name, prompt) in defaultAiDefinitions) {
            val trigger = "$prefix$name"
            if (!conflictsWithExisting(trigger, existingNames, prefix)) {
                val obj = JSONObject()
                obj.put("trigger", trigger)
                obj.put("prompt", prompt)
                obj.put("type", CommandType.AI.name)
                arr.put(obj)
                existingNames += trigger
                added = true
            }
        }
        val editor = prefs.edit()
        if (added) {
            editor.putString("custom_commands", arr.toString())
            invalidateCache()
        }
        editor.putBoolean("ai_commands_seeded", true).apply()
        aiCommandsSeeded = true
    }

    @Volatile
    private var migrating = false

    /**
     * The first alias implementation could persist aliases in command backups or previews.
     * Start this release with a clean alias slate exactly once; after that, aliases belong to
     * the user and are never silently removed again.
     */
    private fun resetAliasesOnce() {
        val alreadyReset = try {
            prefs.getBoolean(PREF_ALIASES_RESET_V1, false)
        } catch (_: ClassCastException) {
            // A restored or manually-corrupted preference must not disable the accessibility
            // service during construction. Remove only the bad marker and retry the migration.
            prefs.edit().remove(PREF_ALIASES_RESET_V1).apply()
            false
        }
        if (alreadyReset) return
        val raw = try {
            prefs.getString("custom_commands", "[]") ?: "[]"
        } catch (_: ClassCastException) {
            prefs.edit().remove("custom_commands").apply()
            "[]"
        }
        val commands = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        for (index in 0 until commands.length()) {
            commands.optJSONObject(index)?.remove("aliases")
        }
        prefs.edit()
            .putString("custom_commands", commands.toString())
            .putBoolean(PREF_ALIASES_RESET_V1, true)
            .apply()
        invalidateCache()
    }

    @Synchronized fun getCommands(): List<Command> {
        if (!aiCommandsSeeded) {
            seedDefaultAiCommands()
        }
        val now = System.currentTimeMillis()
        val cached = cachedCommands
        if (cached != null && now - cacheTimestamp < CACHE_TTL_MS) return cached
        val prefix = getTriggerPrefix()
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        if (cached != null && customStr == cachedCommandsJson && prefix == cachedPrefix) {
            cacheTimestamp = now
            return cached
        }
        // Guard against a corrupted store — an unhandled JSONException here would
        // crash the accessibility service on every text-change event with no recovery.
        val arr = try { JSONArray(customStr) } catch (_: Exception) {
            prefs.edit().putString("custom_commands", "[]").apply()
            JSONArray()
        }
        val customCommands = mutableListOf<Command>()
        val acceptedNames = mutableListOf<String>()
        var needsMigration = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val rawTrigger = obj.optString("trigger", "").trim()
            val prompt = obj.optString("prompt", "").trim().take(MAX_PROMPT_LENGTH)
            if (rawTrigger.isEmpty() || prompt.isEmpty()) continue
            val trigger = migrateTrigger(rawTrigger, prefix)
            if (trigger != rawTrigger || prompt != obj.optString("prompt", "")) needsMigration = true
            if (!isValidCommand(trigger, prompt, prefix) ||
                conflictsWithExisting(trigger, acceptedNames, prefix)
            ) {
                needsMigration = true
                continue
            }

            val aliases = mutableListOf<String>()
            for (rawAlias in readAliases(obj)) {
                val alias = migrateTrigger(rawAlias, prefix)
                if (alias != rawAlias) needsMigration = true
                if (isValidAlias(alias, trigger, prefix) &&
                    !conflictsWithExisting(alias, acceptedNames + trigger + aliases, prefix) &&
                    aliases.none { isTriggerConflict(alias, it) }
                ) {
                    aliases += alias
                } else {
                    needsMigration = true
                }
                if (aliases.size == MAX_ALIASES) break
            }
            customCommands.add(Command(trigger, prompt, false,
                try { CommandType.valueOf(obj.optString("type", CommandType.AI.name)) } catch (_: Exception) { CommandType.AI },
                aliases))
            acceptedNames += trigger
            acceptedNames += aliases
        }
        // Self-heal prefix mismatch (e.g. crash between two apply() calls in setTriggerPrefix)
        if (needsMigration && !migrating) {
            migrating = true
            try {
                setTriggerPrefix(prefix)
                return getCommands()
            } finally {
                migrating = false
            }
        }
        val result = (getBuiltInCommands() + customCommands).sortedByDescending {
            (listOf(it.trigger) + it.aliases).maxOf { trigger -> trigger.length }
        }
        cachedCommands = result
        cachedCommandsJson = customStr
        cachedPrefix = prefix
        cacheTimestamp = System.currentTimeMillis()
        return result
    }

    /**
     * Stores [command], replacing [replacing] in the same write.
     *
     * [replacing] defaults to the command's own trigger, which makes this an upsert; pass the
     * old trigger to rename. The Commands screen used to call removeCustomCommand() then
     * saveCustomCommand() when saving a rename — two separate prefs writes, so a failure between
     * them left the command deleted and not re-added.
     *
     * Returns false if the command is not storable, in which case nothing is written.
     */
    @Synchronized fun saveCustomCommand(command: Command, replacing: String = command.trigger): Boolean {
        val prefix = getTriggerPrefix()
        if (!isValidCommand(command.trigger, command.prompt, prefix) ||
            !areValidAliases(command.aliases, command.trigger, prefix)
        ) return false

        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) { JSONArray() }
        val newArr = JSONArray()
        val existingNames = mutableListOf<String>()
        var replacedExisting = false
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val trigger = obj.optString("trigger")
            if (trigger == replacing || trigger == command.trigger) {
                replacedExisting = true
                continue
            }
            newArr.put(obj)
            if (trigger.isNotBlank()) existingNames += trigger
            existingNames += readAliases(obj)
        }

        // This is the manager's public write boundary, so callers other than the Compose
        // screen (imports, tests, or a future integration) get the same global guarantee:
        // aliases cannot shadow built-ins or another command's trigger/alias.
        if (commandNames(command).any { conflictsWithExisting(it, existingNames, prefix) }) return false
        if (!replacedExisting && newArr.length() >= MAX_CUSTOM_COMMANDS) return false

        val newObj = JSONObject()
        newObj.put("trigger", command.trigger)
        newObj.put("prompt", command.prompt.trim())
        newObj.put("type", command.type.name)
        putAliases(newObj, command.aliases)
        newArr.put(newObj)
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        invalidateCache()
        return true
    }

    @Synchronized fun removeCustomCommand(trigger: String) {
        val customStr = prefs.getString("custom_commands", "[]") ?: "[]"
        val arr = try { JSONArray(customStr) } catch (_: Exception) { JSONArray() }
        val newArr = JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("trigger") != trigger) {
                newArr.put(obj)
            }
        }
        prefs.edit().putString("custom_commands", newArr.toString()).apply()
        invalidateCache()
    }

    @Synchronized fun exportCommands(): String {
        return prefs.getString("custom_commands", "[]") ?: "[]"
    }

    /**
     * Imports commands from a backup, sanitizing entries instead of rejecting the whole file.
     *
     * Only a genuinely malformed file (unparseable JSON) fails hard. Individual entries are
     * cleaned so that backups from older versions — which could store prompts longer than
     * [MAX_PROMPT_LENGTH] or triggers with a stale prefix — still restore: triggers are
     * truncated to [MAX_TRIGGER_LENGTH] and migrated to the current prefix, prompts truncated
     * to [MAX_PROMPT_LENGTH], unknown types default to [CommandType.AI], and unusable entries
     * (blank trigger/prompt) are dropped. The result is capped at [MAX_CUSTOM_COMMANDS].
     */
    @Synchronized fun importCommands(json: String): Boolean {
        return try {
            val arr = JSONArray(json)
            val prefix = getTriggerPrefix()
            val cleaned = JSONArray()
            fun cleanedNames(): List<String> = (0 until cleaned.length()).flatMap { index ->
                val existing = cleaned.optJSONObject(index) ?: return@flatMap emptyList()
                listOf(existing.optString("trigger")) + readAliases(existing)
            }
            for (i in 0 until arr.length()) {
                if (cleaned.length() >= MAX_CUSTOM_COMMANDS) break
                val obj = arr.optJSONObject(i) ?: continue
                var trigger = obj.optString("trigger", "").trim()
                val prompt = obj.optString("prompt", "").take(MAX_PROMPT_LENGTH)
                if (trigger.isEmpty() || prompt.isBlank()) continue
                if (!trigger.startsWith(prefix)) {
                    // Same migration as setTriggerPrefix: strip any leading non-alphanumeric
                    // char, then apply the current prefix.
                    val stripped = if (!trigger[0].isLetterOrDigit()) trigger.substring(1) else trigger
                    trigger = prefix + stripped
                }
                trigger = trigger.take(MAX_TRIGGER_LENGTH)
                if (trigger.length <= prefix.length) continue
                val type = obj.optString("type", CommandType.AI.name)
                if (conflictsWithExisting(trigger, cleanedNames(), prefix)) continue

                val aliases = mutableListOf<String>()
                for (rawAlias in readAliases(obj)) {
                    val alias = migrateTrigger(rawAlias, prefix)
                    if (!isValidAlias(alias, trigger, prefix) ||
                        conflictsWithExisting(alias, cleanedNames() + trigger + aliases, prefix) ||
                        aliases.any { isTriggerConflict(alias, it) }
                    ) continue
                    aliases += alias
                    if (aliases.size == MAX_ALIASES) break
                }
                val out = JSONObject()
                out.put("trigger", trigger)
                out.put("prompt", prompt)
                out.put("type",
                    if (type == CommandType.TEXT_REPLACER.name) CommandType.TEXT_REPLACER.name else CommandType.AI.name)
                putAliases(out, aliases)
                cleaned.put(out)
            }
            if (arr.length() > 0 && cleaned.length() == 0) return false
            prefs.edit().putString("custom_commands", cleaned.toString()).apply()
            invalidateCache()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun findCommand(text: String): Command? = findCommandMatch(text)?.command

    /** Returns the command and the exact trigger or alias that matched the text suffix. */
    fun findCommandMatch(text: String): CommandMatch? {
        val commands = getCommands()
        for (cmd in commands) {
            if (cmd.trigger.endsWith("translate:xx")) continue
            val candidates = sequenceOf(cmd.trigger).plus(cmd.aliases.asSequence())
            val matched = candidates
                .filter { text.endsWith(it) }
                .maxByOrNull { it.length }
            if (matched != null) return CommandMatch(cmd, matched)
        }
        val prefix = getTriggerPrefix()
        // Translate trigger — intentionally accepts any 2-5 char alphanumeric language code.
        val translatePrefix = "${prefix}translate:"
        val translateIdx = text.lastIndexOf(translatePrefix)
        if (translateIdx >= 0) {
            val langPart = text.substring(translateIdx + translatePrefix.length)
            if (langPart.length in 2..5 && langPart.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
                val command = Command("${translatePrefix}$langPart", "Translate to language code '$langPart'.", true)
                return CommandMatch(command, command.trigger)
            }
        }
        return null
    }

}
