package com.jcversa.swiftslate.model

/** Small curated fallback catalogs used before a provider's /models list is fetched. */
object NvidiaModels {
    const val DEFAULT = "nvidia/nemotron-3-super-120b-a12b"

    fun sanitize(value: String?): String = value?.trim().orEmpty().ifBlank { DEFAULT }
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
