package com.jcversa.swiftslate.model

/**
 * Single source of truth for the SharedPreferences keys used by the
 * provider/model configuration flow. Centralizing these prevents silent
 * breakage from mistyped string literals scattered across UI, service, and
 * client code.
 *
 * Values are unchanged from the literals previously used inline, so existing
 * stored preferences continue to resolve identically.
 */
object PrefKeys {
    /** Active provider (see [ProviderType]); each provider has its own key namespace. */
    const val PROVIDER_TYPE = "provider_type"

    /** Selected Gemini model id. */
    const val GEMINI_MODEL = "model"

    /** Selected Groq model id. */
    const val GROQ_MODEL = "groq_model"

    /** Selected NVIDIA NIM model id. */
    const val NVIDIA_MODEL = "nvidia_model"

    /** Selected OpenRouter model id. */
    const val OPENROUTER_MODEL = "openrouter_model"

    /** Selected DeepSeek model id. */
    const val DEEPSEEK_MODEL = "deepseek_model"

    /** Custom (OpenAI-compatible) model id. */
    const val CUSTOM_MODEL = "custom_model"

    /** Custom (OpenAI-compatible) endpoint base URL. */
    const val CUSTOM_ENDPOINT = "custom_endpoint"

    /** Sampling temperature (Float). */
    const val TEMPERATURE = "temperature"

    /** Epoch millis when structured output was last disabled (0 = never). */
    const val STRUCTURED_OUTPUT_DISABLED_AT = "structured_output_disabled_at"

    /** Whether AI requests are disabled while local text replacers remain available. */
    const val PRIVACY_MODE = "privacy_mode"

    /** Whether AI text replacements are revealed progressively in the focused field. */
    const val TYPING_ANIMATION_ENABLED = "typing_animation_enabled"

    /** Whether the optional local command history is enabled. */
    const val HISTORY_ENABLED = "history_enabled"

    /** Number of days to keep optional local history entries. */
    const val HISTORY_RETENTION_DAYS = "history_retention_days"

    /** Whether the first-run setup assistant has been completed or dismissed. */
    const val ONBOARDING_COMPLETED = "onboarding_completed"
}
