package com.jcversa.swiftslate.model

/** Stable identifiers persisted for the provider selected in Settings. */
object ProviderType {
    const val GEMINI = "gemini"
    const val GROQ = "groq"
    const val NVIDIA = "nvidia"
    const val OPENROUTER = "openrouter"
    const val DEEPSEEK = "deepseek"
    const val CUSTOM = "custom"

    private val VALID = setOf(GEMINI, GROQ, NVIDIA, OPENROUTER, DEEPSEEK, CUSTOM)

    /** Unknown or legacy values safely fall back to Gemini. */
    fun sanitize(value: String?): String = if (value in VALID) value!! else GEMINI
}
