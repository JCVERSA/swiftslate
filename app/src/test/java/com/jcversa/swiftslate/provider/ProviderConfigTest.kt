package com.jcversa.swiftslate.provider

import com.jcversa.swiftslate.model.GeminiModels
import com.jcversa.swiftslate.model.GroqModels
import com.jcversa.swiftslate.model.NvidiaModels
import com.jcversa.swiftslate.model.OpenRouterModels
import com.jcversa.swiftslate.model.DeepSeekModels
import com.jcversa.swiftslate.model.PrefKeys
import com.jcversa.swiftslate.model.ProviderType
import org.junit.Assert.*
import org.junit.Test

/** Pure unit tests for provider routing/config. No Android deps. */
class ProviderConfigTest {

    @Test
    fun forType_routes_each_provider() {
        assertSame(GeminiConfig, Providers.forType(ProviderType.GEMINI))
        assertSame(GroqConfig, Providers.forType(ProviderType.GROQ))
        assertSame(NvidiaConfig, Providers.forType(ProviderType.NVIDIA))
        assertSame(OpenRouterConfig, Providers.forType(ProviderType.OPENROUTER))
        assertSame(DeepSeekConfig, Providers.forType(ProviderType.DEEPSEEK))
        assertSame(CustomConfig, Providers.forType(ProviderType.CUSTOM))
    }

    @Test
    fun forType_defaults_to_gemini_for_missing_value_only() {
        assertSame(GeminiConfig, Providers.forType(null))
        assertSame(GeminiConfig, Providers.forType("nonsense"))
    }

    @Test
    fun forStoredType_rejects_unknown_values_instead_of_routing_them() {
        assertSame(GeminiConfig, Providers.forStoredType(null))
        assertSame(GeminiConfig, Providers.forStoredType(ProviderType.GEMINI))
        assertNull(Providers.forStoredType("nonsense"))
    }

    @Test
    fun transports_are_correct() {
        assertEquals(Transport.GEMINI_NATIVE, GeminiConfig.transport)
        assertEquals(Transport.OPENAI_COMPAT, GroqConfig.transport)
        assertEquals(Transport.OPENAI_COMPAT, NvidiaConfig.transport)
        assertEquals(Transport.OPENAI_COMPAT, OpenRouterConfig.transport)
        assertEquals(Transport.OPENAI_COMPAT, DeepSeekConfig.transport)
        assertEquals(Transport.OPENAI_COMPAT, CustomConfig.transport)
    }

    @Test
    fun model_pref_keys_and_defaults() {
        assertEquals(PrefKeys.GEMINI_MODEL, GeminiConfig.modelPrefKey)
        assertEquals(PrefKeys.GROQ_MODEL, GroqConfig.modelPrefKey)
        assertEquals(PrefKeys.NVIDIA_MODEL, NvidiaConfig.modelPrefKey)
        assertEquals(PrefKeys.OPENROUTER_MODEL, OpenRouterConfig.modelPrefKey)
        assertEquals(PrefKeys.DEEPSEEK_MODEL, DeepSeekConfig.modelPrefKey)
        assertEquals(PrefKeys.CUSTOM_MODEL, CustomConfig.modelPrefKey)
        assertEquals(GeminiModels.DEFAULT, GeminiConfig.defaultModel)
        assertEquals(GroqModels.DEFAULT, GroqConfig.defaultModel)
        assertEquals(NvidiaModels.DEFAULT, NvidiaConfig.defaultModel)
        assertEquals(OpenRouterModels.DEFAULT, OpenRouterConfig.defaultModel)
        assertEquals(DeepSeekModels.DEFAULT, DeepSeekConfig.defaultModel)
        assertEquals("", CustomConfig.defaultModel)
    }

    @Test
    fun endpoint_resolution() {
        assertEquals(GroqConfig.ENDPOINT, GroqConfig.resolveEndpoint("ignored"))
        assertEquals(NvidiaConfig.ENDPOINT, NvidiaConfig.resolveEndpoint("ignored"))
        assertEquals(OpenRouterConfig.ENDPOINT, OpenRouterConfig.resolveEndpoint("ignored"))
        assertEquals(DeepSeekConfig.ENDPOINT, DeepSeekConfig.resolveEndpoint("ignored"))
        assertEquals("", GeminiConfig.resolveEndpoint("ignored"))
        assertEquals("https://my.endpoint/v1", CustomConfig.resolveEndpoint("https://my.endpoint/v1"))
    }

    @Test
    fun new_provider_models_are_trimmed_and_have_stable_defaults() {
        assertEquals(NvidiaModels.DEFAULT, NvidiaConfig.sanitizeModel("  "))
        assertEquals("meta/custom", NvidiaConfig.sanitizeModel(" meta/custom "))
        assertEquals(OpenRouterModels.DEFAULT, OpenRouterConfig.sanitizeModel(null))
        assertEquals(DeepSeekModels.DEFAULT, DeepSeekConfig.sanitizeModel(""))
        assertEquals("deepseek-chat", DeepSeekConfig.sanitizeModel(" deepseek-chat "))
    }

    @Test
    fun jsonObjectMode_only_groq_when_enabled() {
        assertTrue(GroqConfig.useJsonObjectMode(true))
        assertFalse(GroqConfig.useJsonObjectMode(false))
        assertFalse(GeminiConfig.useJsonObjectMode(true))
        assertFalse(CustomConfig.useJsonObjectMode(true))
        assertFalse(DeepSeekConfig.useJsonObjectMode(true))
        assertFalse(DeepSeekConfig.useJsonObjectMode(false))
        assertFalse(NvidiaConfig.useJsonObjectMode(true))
        assertFalse(OpenRouterConfig.useJsonObjectMode(true))
    }

    @Test
    fun isConfigured_only_custom_requires_both() {
        assertTrue(GeminiConfig.isConfigured("", ""))
        assertTrue(GroqConfig.isConfigured("m", ""))
        assertTrue(CustomConfig.isConfigured("m", "https://x"))
        assertFalse(CustomConfig.isConfigured("", "https://x"))
        assertFalse(CustomConfig.isConfigured("m", ""))
        assertFalse(CustomConfig.isConfigured("m", "   "))
    }

    @Test
    fun custom_model_is_trimmed_and_null_safe() {
        assertEquals("gpt-4o", CustomConfig.sanitizeModel("  gpt-4o  "))
        assertEquals("", CustomConfig.sanitizeModel(null))
    }

    @Test
    fun gemini_config_sanitizes_and_exposes_thinking_level() {
        assertEquals(GeminiModels.DEFAULT, GeminiConfig.sanitizeModel("gemini-2.5-flash-lite")) // retired
        assertEquals("gemini-3.7-pro", GeminiConfig.sanitizeModel("  gemini-3.7-pro  ")) // dynamic pass-through
        assertEquals("minimal", GeminiConfig.thinkingLevel(GeminiModels.DEFAULT))
    }

    @Test
    fun groq_config_delegates_reasoning_params() {
        assertEquals(
            mapOf("reasoning_effort" to "medium", "include_reasoning" to false),
            GroqConfig.reasoningParams("openai/gpt-oss-120b")
        )
        assertTrue(GroqConfig.reasoningParams("llama-3.1-8b-instant").isEmpty())
        // Non-Gemini providers expose no thinking level.
        assertNull(GroqConfig.thinkingLevel("openai/gpt-oss-120b"))
        assertNull(CustomConfig.thinkingLevel("anything"))
        // Non-Groq providers add no reasoning params.
        assertTrue(GeminiConfig.reasoningParams("x").isEmpty())
        assertTrue(CustomConfig.reasoningParams("x").isEmpty())
    }

    // --- EndpointValidator ---

    @Test
    fun endpointValidator_acceptsHttpsPublicAndPrivate() {
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("https://api.example.com/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("https://192.168.1.5:8080/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("https://8.8.8.8/v1"))
    }

    @Test
    fun endpointValidator_acceptsHttpForPrivateLanHosts() {
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://localhost:11434/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://127.0.0.1:8080/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://10.0.2.2:8080/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://10.1.2.3:8080/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://192.168.1.5:8080/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://172.16.0.1:8080/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://172.31.255.254:8080/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://169.254.0.1:8080/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://100.64.0.1:8080/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://100.127.255.254:8080/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://my-nas.local:8080/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://[::1]:8080/v1"))
    }

    @Test
    fun endpointValidator_rejectsHttpForPublicHosts() {
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http://api.example.com/v1"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http://8.8.8.8/v1"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http://192.168.5.5.5:8080/v1"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http://172.15.0.1:8080/v1"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http://172.32.0.1:8080/v1"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http://100.63.255.254:8080/v1"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http://100.128.0.1:8080/v1"))
    }

    @Test
    fun endpointValidator_rejectsMalformedOrMissingScheme() {
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate(""))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("api.example.com/v1"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("ftp://example.com/v1"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http://"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http:// 192.168.1.5:8080"))
    }

    @Test
    fun endpointValidator_schemeIsCaseInsensitive() {
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("HTTPS://api.example.com/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("HTTP://192.168.1.5:8080/v1"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("HTTP://api.example.com/v1"))
    }

    @Test
    fun endpointValidator_rejectsHostlessHttps() {
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("https://"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("https:// "))
    }

    @Test
    fun endpointValidator_acceptsHttpForPrivateIPv6Literals() {
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://[fe80::1]:8080/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://[febf::1]:8080/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://[fc00::1]/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://[fd12:3456::99]/v1"))
        assertEquals(EndpointValidator.Error.NONE, EndpointValidator.validate("http://[::ffff:192.168.1.5]/v1"))
    }

    @Test
    fun endpointValidator_rejectsHttpForNonPrivateIPv6Literals() {
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http://[2001:db8::1]/v1"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http://[ff02::1]/v1"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http://[::]/v1"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http://[fec0::1]/v1"))
        assertEquals(EndpointValidator.Error.INVALID, EndpointValidator.validate("http://[::ffff:8.8.8.8]/v1"))
    }
}
