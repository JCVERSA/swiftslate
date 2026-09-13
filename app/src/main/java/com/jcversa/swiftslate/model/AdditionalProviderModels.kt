package com.jcversa.swiftslate.model

/** Small curated fallback catalogs used before a provider's /models list is fetched. */
object NvidiaModels {
    const val DEFAULT = "nvidia/nemotron-3-super-120b-a12b"

    fun sanitize(value: String?): String = value?.trim().orEmpty().ifBlank { DEFAULT }

    /**
     * Nemotron Super is a reasoning model and enables thinking by default. SwiftSlate's
     * requests are short, single-pass text transformations, so the reasoning trace adds
     * latency without improving the requested operation. The API exposes this as a chat
     * template flag rather than reasoning_effort.
     */
    fun shouldDisableThinking(model: String): Boolean =
        model.contains("nemotron-3-super", ignoreCase = true)
}

object OpenRouterModels {
    /** OpenRouter's free router chooses an eligible free model for the request. */
    const val DEFAULT = "openrouter/free"

    fun sanitize(value: String?): String = value?.trim().orEmpty().ifBlank { DEFAULT }
}

object DeepSeekModels {
    const val DEFAULT = "deepseek-flash"

    fun sanitize(value: String?): String = value?.trim().orEmpty().ifBlank { DEFAULT }
}
