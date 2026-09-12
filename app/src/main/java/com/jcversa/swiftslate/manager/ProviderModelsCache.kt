package com.jcversa.swiftslate.manager

import com.jcversa.swiftslate.model.ProviderType
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime cache of model lists fetched from provider /models endpoints.
 * Session-only by design: stale catalogs never outlive the app process.
 */
object ProviderModelsCache {
    data class Entry(val models: List<String>, val attempted: Boolean)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun get(type: String): Entry? = when (type) {
        ProviderType.GEMINI, ProviderType.GROQ,
        ProviderType.NVIDIA, ProviderType.OPENROUTER, ProviderType.DEEPSEEK -> entries[type]
        else -> null
    }

    fun put(type: String, entry: Entry) {
        when (type) {
            ProviderType.GEMINI, ProviderType.GROQ,
            ProviderType.NVIDIA, ProviderType.OPENROUTER, ProviderType.DEEPSEEK -> entries[type] = entry
        }
    }
}
