package com.jcversa.swiftslate.manager

import android.content.Context
import android.content.SharedPreferences
import com.jcversa.swiftslate.model.HistoryEntry
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Optional, local-only command history.
 *
 * History is disabled by default. When enabled, only the latest [MAX_ENTRIES] successful
 * operations are kept in the app's private preferences. API keys and secrets are never written
 * here. Callers own the decision to record an entry, so failed and refused commands never appear.
 */
class HistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREFS_NAME = "history"
        const val PREF_ENABLED = "history_enabled"
        const val PREF_RETENTION_DAYS = "history_retention_days"
        const val DEFAULT_RETENTION_DAYS = 30
        const val MAX_RETENTION_DAYS = 365
        const val MAX_ENTRIES = 100
        private const val PREF_ENTRIES = "entries"

        private fun safeJson(value: String): JSONArray =
            try { JSONArray(value) } catch (_: Exception) { JSONArray() }

        private fun readEntry(obj: JSONObject): HistoryEntry? {
            val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
            val command = obj.optString("command").takeIf { it.isNotBlank() } ?: return null
            val input = obj.optString("input")
            val output = obj.optString("output").takeIf { it.isNotBlank() } ?: return null
            val createdAt = obj.optLong("created_at", 0L)
            if (createdAt <= 0L) return null
            return HistoryEntry(
                id = id,
                command = command,
                input = input,
                output = output,
                createdAt = createdAt,
                provider = obj.optString("provider", "")
            )
        }
    }

    val isEnabled: Boolean
        get() = try { prefs.getBoolean(PREF_ENABLED, false) } catch (_: Exception) { false }

    val retentionDays: Int
        get() = try {
            prefs.getInt(PREF_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
                .coerceIn(1, MAX_RETENTION_DAYS)
        } catch (_: Exception) {
            DEFAULT_RETENTION_DAYS
        }

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply()
        if (!enabled) clear()
    }

    fun setRetentionDays(days: Int) {
        prefs.edit().putInt(PREF_RETENTION_DAYS, days.coerceIn(1, MAX_RETENTION_DAYS)).apply()
        prune()
    }

    /** Records a successful result only when the user opted into local history. */
    @Synchronized
    fun record(command: String, input: String, output: String, provider: String) {
        if (!isEnabled || output.isBlank()) return
        val now = System.currentTimeMillis()
        val entries = readEntriesInternal().toMutableList()
        entries.add(
            HistoryEntry(
                id = UUID.randomUUID().toString(),
                command = command.take(200),
                input = input.take(20_000),
                output = output.take(20_000),
                createdAt = now,
                provider = provider.take(120)
            )
        )
        writeEntries(entries.sortedByDescending { it.createdAt }.take(MAX_ENTRIES))
    }

    fun getEntries(): List<HistoryEntry> = readEntriesInternal()

    @Synchronized
    fun delete(id: String) {
        writeEntries(readEntriesInternal().filterNot { it.id == id })
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(PREF_ENTRIES).apply()
    }

    @Synchronized
    fun prune() {
        writeEntries(readEntriesInternal())
    }

    private fun readEntriesInternal(): List<HistoryEntry> {
        val cutoff = System.currentTimeMillis() - retentionDays * 24L * 60L * 60L * 1000L
        val raw = try { prefs.getString(PREF_ENTRIES, "[]") ?: "[]" } catch (_: Exception) { "[]" }
        val entries = safeJson(raw).let { array ->
            (0 until array.length()).mapNotNull { index -> readEntry(array.optJSONObject(index) ?: JSONObject()) }
        }.filter { it.createdAt >= cutoff }
            .sortedByDescending { it.createdAt }
            .take(MAX_ENTRIES)
        // Opportunistically remove expired/corrupt records without blocking callers on a second
        // read. This is a private preferences write and contains no secret material.
        if (entries.size != safeJson(raw).length()) writeEntries(entries)
        return entries
    }

    private fun writeEntries(entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.sortedByDescending { it.createdAt }.take(MAX_ENTRIES).forEach { entry ->
            array.put(JSONObject().apply {
                put("id", entry.id)
                put("command", entry.command)
                put("input", entry.input)
                put("output", entry.output)
                put("created_at", entry.createdAt)
                put("provider", entry.provider)
            })
        }
        prefs.edit().putString(PREF_ENTRIES, array.toString()).apply()
    }
}
