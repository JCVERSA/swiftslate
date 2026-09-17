package com.jcversa.swiftslate.provider

import com.jcversa.swiftslate.model.DeepSeekModels
import com.jcversa.swiftslate.model.GeminiModels
import com.jcversa.swiftslate.model.GroqModels
import com.jcversa.swiftslate.model.NvidiaModels
import com.jcversa.swiftslate.model.OpenRouterModels
import com.jcversa.swiftslate.model.PrefKeys
import com.jcversa.swiftslate.model.ProviderType
import org.json.JSONObject

/** Which transport client handles a provider's requests. */
enum class Transport { GEMINI_NATIVE, OPENAI_COMPAT }

/**
 * Per-provider configuration: everything the request pipeline needs to know
 * about a provider, in one place. Implementations are pure (no Android/network
 * dependencies) so they are straightforward to test.
 */
interface ProviderConfig {
    val type: String
    val transport: Transport
    val modelPrefKey: String
    val defaultModel: String

    fun sanitizeModel(stored: String?): String
    fun resolveEndpoint(customEndpoint: String): String
    fun reasoningParams(model: String): Map<String, Any> = emptyMap()
    fun thinkingLevel(model: String): String? = null
    fun useJsonObjectMode(structuredOutputEnabled: Boolean): Boolean = false
    fun isConfigured(model: String, endpoint: String): Boolean = true
}

/** Gemini — native API, model-gated thinking level (spec-driven). */
object GeminiConfig : ProviderConfig {
    override val type = ProviderType.GEMINI
    override val transport = Transport.GEMINI_NATIVE
    override val modelPrefKey = PrefKeys.GEMINI_MODEL
    override val defaultModel = GeminiModels.DEFAULT
    override fun sanitizeModel(stored: String?): String = GeminiModels.sanitize(stored)
    override fun resolveEndpoint(customEndpoint: String): String = ""
    override fun thinkingLevel(model: String): String? = GeminiModels.thinkingLevel(model)
}

/** Groq — OpenAI-compatible, fixed endpoint, per-model reasoning controls. */
object GroqConfig : ProviderConfig {
    const val ENDPOINT = "https://api.groq.com/openai/v1"

    override val type = ProviderType.GROQ
    override val transport = Transport.OPENAI_COMPAT
    override val modelPrefKey = PrefKeys.GROQ_MODEL
    override val defaultModel = GroqModels.DEFAULT
    override fun sanitizeModel(stored: String?): String = GroqModels.sanitize(stored)
    override fun resolveEndpoint(customEndpoint: String): String = ENDPOINT
    override fun reasoningParams(model: String): Map<String, Any> = GroqModels.reasoningParams(model)
    override fun useJsonObjectMode(structuredOutputEnabled: Boolean): Boolean = structuredOutputEnabled
}

/** NVIDIA NIM — OpenAI-compatible API at integrate.api.nvidia.com. */
object NvidiaConfig : ProviderConfig {
    const val ENDPOINT = "https://integrate.api.nvidia.com/v1"

    override val type = ProviderType.NVIDIA
    override val transport = Transport.OPENAI_COMPAT
    override val modelPrefKey = PrefKeys.NVIDIA_MODEL
    override val defaultModel = NvidiaModels.DEFAULT
    override fun sanitizeModel(stored: String?): String = NvidiaModels.sanitize(stored)
    override fun resolveEndpoint(customEndpoint: String): String = ENDPOINT

    override fun reasoningParams(model: String): Map<String, Any> =
        if (NvidiaModels.shouldDisableThinking(model)) {
            mapOf(
                "chat_template_kwargs" to JSONObject().apply {
                    put("enable_thinking", false)
                }
            )
        } else {
            emptyMap()
        }
}

/** OpenRouter — OpenAI-compatible gateway with a user-owned OpenRouter key. */
object OpenRouterConfig : ProviderConfig {
    const val ENDPOINT = "https://openrouter.ai/api/v1"

    override val type = ProviderType.OPENROUTER
    override val transport = Transport.OPENAI_COMPAT
    override val modelPrefKey = PrefKeys.OPENROUTER_MODEL
    override val defaultModel = OpenRouterModels.DEFAULT
    override fun sanitizeModel(stored: String?): String = OpenRouterModels.sanitize(stored)
    override fun resolveEndpoint(customEndpoint: String): String = ENDPOINT
}

/** DeepSeek — OpenAI-compatible API at api.deepseek.com (without a /v1 suffix). */
object DeepSeekConfig : ProviderConfig {
    const val ENDPOINT = "https://api.deepseek.com"

    override val type = ProviderType.DEEPSEEK
    override val transport = Transport.OPENAI_COMPAT
    override val modelPrefKey = PrefKeys.DEEPSEEK_MODEL
    override val defaultModel = DeepSeekModels.DEFAULT
    override fun sanitizeModel(stored: String?): String = DeepSeekModels.sanitize(stored)
    override fun resolveEndpoint(customEndpoint: String): String = ENDPOINT
    override fun useJsonObjectMode(structuredOutputEnabled: Boolean): Boolean = false
}

/** Custom OpenAI-compatible endpoint — user-supplied endpoint and model. */
object CustomConfig : ProviderConfig {
    override val type = ProviderType.CUSTOM
    override val transport = Transport.OPENAI_COMPAT
    override val modelPrefKey = PrefKeys.CUSTOM_MODEL
    override val defaultModel = ""
    override fun sanitizeModel(stored: String?): String = stored?.trim() ?: ""
    override fun resolveEndpoint(customEndpoint: String): String = customEndpoint
    override fun isConfigured(model: String, endpoint: String): Boolean =
        model.isNotBlank() && endpoint.isNotBlank()
}

/** Registry resolving a stored provider value to its [ProviderConfig]. */
object Providers {
    /**
     * Resolves a provider for legacy/UI callers. A missing value means the documented
     * first-run Gemini default; an invalid stored value must use [forStoredType] instead
     * so it cannot silently route user text to another provider.
     */
    fun forType(type: String?): ProviderConfig = when (ProviderType.sanitize(type)) {
        ProviderType.GROQ -> GroqConfig
        ProviderType.NVIDIA -> NvidiaConfig
        ProviderType.OPENROUTER -> OpenRouterConfig
        ProviderType.DEEPSEEK -> DeepSeekConfig
        ProviderType.CUSTOM -> CustomConfig
        else -> GeminiConfig
    }

    /** Null means no preference yet; non-null unknown values are a configuration error. */
    fun forStoredType(type: String?): ProviderConfig? =
        if (type == null || ProviderType.isValid(type)) forType(type) else null
}
