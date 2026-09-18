package com.jcversa.swiftslate.model

/** Stable identifiers persisted for the provider selected in Settings. */
object ProviderType {
    const val GEMINI = "gemini"
    const val GROQ = "groq"
    const val NVIDIA = "nvidia"
    const val OPENROUTER = "openrouter"
    const val DEEPSEEK = "deepseek"
    const val CUSTOM = "custom"

    /** Stable order used by configuration exports and provider-wide maintenance operations. */
    val ALL = listOf(GEMINI, GROQ, NVIDIA, OPENROUTER, DEEPSEEK, CUSTOM)

    private val VALID = ALL.toSet()

    /** Whether a stored provider value is known. Null means a first-run default is allowed. */
    fun isValid(value: String?): Boolean = value == null || value in VALID

    /** Returns a known stored value, or null when an existing value is invalid. */
    fun storedOrNull(value: String?): String? = value?.takeIf { it in VALID }

    /** Compatibility helper for UI-only paths where null means the documented Gemini default. */
    fun sanitize(value: String?): String = storedOrNull(value) ?: GEMINI
}
