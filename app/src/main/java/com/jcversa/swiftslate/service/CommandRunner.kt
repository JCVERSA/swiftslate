package com.jcversa.swiftslate.service

import android.content.Context
import com.jcversa.swiftslate.R
import com.jcversa.swiftslate.api.ApiClientUtils
import com.jcversa.swiftslate.api.ApiError
import com.jcversa.swiftslate.api.ApiException
import com.jcversa.swiftslate.api.GeminiClient
import com.jcversa.swiftslate.api.OpenAICompatibleClient
import com.jcversa.swiftslate.manager.KeyManager
import com.jcversa.swiftslate.model.PrefKeys
import com.jcversa.swiftslate.model.ProviderType
import com.jcversa.swiftslate.provider.Providers
import com.jcversa.swiftslate.provider.Transport
import java.util.Locale

sealed interface CommandOutcome {
    data class Success(val text: String) : CommandOutcome
    /** The model refused in-band. Its answer, not a fault — re-running the same prompt won't help. */
    data object Refusal : CommandOutcome
    /** Nothing was sent and nothing will be until the user changes something. */
    data class Unavailable(val message: String) : CommandOutcome
    /** A request was attempted and failed. Retrying may work. */
    data class Failure(val message: String) : CommandOutcome
}

private const val DEFAULT_TEMPERATURE = 0.5f
private const val STRUCTURED_OUTPUT_RETRY_MS = 86_400_000L // re-try structured output after 24h
/**
 * Largest selection transformable in one request (UTF-16 chars). Mirrors the 1 MiB response
 * bound: anything this large exceeds every free-tier token budget and would fail at the
 * provider anyway, so fail fast without building a multi-megabyte request body or burning
 * metered data. Deliberately generous — ordinary use (and the largest sane selections) is
 * orders of magnitude below it.
 */
internal const val MAX_INPUT_CHARS = 1_000_000

/** Pure predicate behind the input-size guard, so the policy is unit-testable. */
internal fun isInputTooLarge(text: String): Boolean = text.length > MAX_INPUT_CHARS

/**
 * Sampling-temperature bounds, matching the Settings slider (`valueRange = 0f..2f`) and the
 * range Gemini/Groq/OpenAI-compatible providers accept.
 */
internal const val MIN_TEMPERATURE = 0.0
internal const val MAX_TEMPERATURE = 2.0

/**
 * Pure policy behind the temperature guard, so it is unit-testable. Prefs are a trust
 * boundary (corruption, backup/restore, hand edits): NaN falls back to the default and
 * anything else is clamped, so a bad float can never reach the provider as a 400-class
 * failure. coerceIn alone would not suffice — NaN comparisons are false, so NaN passes
 * straight through it.
 */
internal fun sanitizeTemperature(stored: Float): Double =
    if (stored.isNaN()) DEFAULT_TEMPERATURE.toDouble()
    else stored.toDouble().coerceIn(MIN_TEMPERATURE, MAX_TEMPERATURE)

/**
 * Everything a trigger command does between "user asked" and "text came back": provider
 * resolution, key rotation, rate-limit benching and error mapping. Both entry points call this
 * — the accessibility service for a typed `?trigger`, the text-selection sheet for a tapped
 * one — so a fix to the request policy lands in both at once.
 *
 * Knows nothing about how the result is delivered: no nodes, no toasts, no UI state. Suspends
 * on the caller's dispatcher and reads disk (prefs, Keystore), so call it off the main thread.
 *
 * @param onFirstAttempt run just before the first request actually goes out — after it is known
 *   that a usable key exists. The service starts its inline spinner here.
 */
suspend fun runTextCommand(
    context: Context,
    keyManager: KeyManager,
    geminiClient: GeminiClient,
    openAIClient: OpenAICompatibleClient,
    prompt: String,
    text: String,
    onFirstAttempt: () -> Unit = {}
): CommandOutcome {
    // keys_keystore_error rather than a "reinstall" message: the usual cause is the Keystore key
    // being invalidated by a lock-screen change, where re-adding the keys is enough.
    if (!keyManager.keystoreAvailable) {
        return CommandOutcome.Unavailable(context.getString(R.string.keys_keystore_error))
    }

    // Nothing is sent and retrying cannot help, so this is Unavailable rather than Failure:
    // the field is untouched (the spinner only starts before the first real attempt) and the
    // text-selection sheet offers no retry button for it. Reuses the existing localized
    // length message — no new string that would ship English-only to 40 locales.
    if (isInputTooLarge(text)) {
        return CommandOutcome.Unavailable(context.getString(R.string.error_input_too_long))
    }

    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    if (prefs.getBoolean(PrefKeys.PRIVACY_MODE, false)) {
        // Text replacer commands never enter this function, so privacy mode keeps all local
        // commands available while making the no-network guarantee explicit for every AI entry
        // point (typed trigger, text-selection action, and command preview).
        return CommandOutcome.Unavailable(context.getString(R.string.privacy_mode_blocked))
    }

    val provider = Providers.forStoredType(prefs.getString(PrefKeys.PROVIDER_TYPE, null))
        ?: return CommandOutcome.Unavailable(context.getString(R.string.error_provider_selection_invalid))
    val providerType = provider.type
    val model = provider.sanitizeModel(prefs.getString(provider.modelPrefKey, provider.defaultModel))
    val endpoint = provider.resolveEndpoint(prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: "")
    if (!provider.isConfigured(model, endpoint)) {
        return CommandOutcome.Unavailable(context.getString(R.string.toast_custom_not_configured))
    }
    val temperature = sanitizeTemperature(prefs.getFloat(PrefKeys.TEMPERATURE, DEFAULT_TEMPERATURE))
    val useStructuredOutput = System.currentTimeMillis() -
        prefs.getLong(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT, 0L) > STRUCTURED_OUTPUT_RETRY_MS

    var lastErrorMsg: String? = null
    var lastErrorWasRateLimit = false
    var lastErrorWasPermission = false
    var lastFailedKey: String? = null
    var started = false
    val tried = mutableSetOf<String>()

    // One attempt per configured key; getNextKey skips the ones already tried, plus any that are
    // benched for rate limiting or known-invalid.
    val maxAttempts = keyManager.getKeys(providerType).size.coerceAtLeast(1)
    while (tried.size < maxAttempts) {
        val key = keyManager.getNextKey(tried, providerType) ?: break
        tried.add(key)
        if (!started) {
            started = true
            onFirstAttempt()
        }

        val result = when (provider.transport) {
            Transport.OPENAI_COMPAT -> openAIClient.generate(
                prompt, text, key, model, temperature, endpoint,
                useJsonObjectMode = provider.useJsonObjectMode(useStructuredOutput),
                extraParams = provider.reasoningParams(model),
                maxOutputTokens = if (providerType == ProviderType.NVIDIA) {
                    ApiClientUtils.suggestedMaxOutputTokens(text)
                } else {
                    null
                })
            Transport.GEMINI_NATIVE -> geminiClient.generate(
                prompt, text, key, model, temperature, useStructuredOutput,
                thinkingLevel = provider.thinkingLevel(model))
        }

        result.onSuccess { generated ->
            if (ApiClientUtils.isModelRefusal(generated.text)) return CommandOutcome.Refusal
            if (generated.structuredOutputFailed) {
                prefs.edit()
                    .putLong(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT, System.currentTimeMillis())
                    .apply()
            }
            // Keep the truncation warning localized and shared by both entry points rather than
            // leaving callers to duplicate it (or clients to inject an English-only string).
            val outputText = if (generated.truncated) {
                generated.text + "\n\n" + context.getString(R.string.note_response_truncated)
            } else {
                generated.text
            }
            return CommandOutcome.Success(outputText)
        }

        val error = result.exceptionOrNull()
        val msg = error?.message ?: ""
        lastErrorMsg = msg
        when (val apiError = (error as? ApiException)?.apiError) {
            is ApiError.RateLimit -> {
                lastErrorWasRateLimit = true
                keyManager.reportRateLimit(key, apiError.retryAfterSeconds?.toLong() ?: 60, providerType)
            }
            is ApiError.InvalidKey -> {
                lastErrorWasRateLimit = false
                // Server-side sign-in failures (Ollama Cloud) are not the key's fault:
                // don't bench it, don't record it as a failed key, and don't let an
                // earlier iteration's permission verdict override the sign-in message.
                if (msg.contains(ApiClientUtils.SIGNIN_REQUIRED_MARKER)) {
                    lastFailedKey = null
                    lastErrorWasPermission = false
                } else {
                    lastFailedKey = key
                    // Distinguish "this key is bad" from "this key may not use this model" (both
                    // arrive as 401/403) so the final message names the right fix.
                    val m = msg.lowercase(Locale.ROOT)
                    lastErrorWasPermission = m.contains("permission") ||
                        m.contains("does not have access") || m.contains("not been used in project")
                    // Never bench the last remaining key: with no fallback to rotate to, the
                    // 15-minute invalid mark just turned every later trigger into "all keys
                    // invalid" with no recovery path until a process restart.
                    if (keyManager.getKeys(providerType).size > 1) {
                        keyManager.markInvalid(key, providerType)
                    }
                }
            }
            // 5xx — try the next key.
            is ApiError.ServerError -> lastErrorWasRateLimit = false
            else -> {
                // Rotating keys cannot help: RequestTooLarge is a per-account token budget, the
                // rest are non-retryable. Clear the flag so a 400 arriving after an earlier 429
                // is not reported as a rate limit with a bogus countdown.
                lastErrorWasRateLimit = false
                break
            }
        }
    }

    val waitMs = keyManager.getShortestWaitTimeMs(providerType)
    val failedKey = lastFailedKey
    val raw = lastErrorMsg
    return CommandOutcome.Failure(
        when {
            // Prefer the message carrying the actual wait time, but only when the last error
            // really was a rate limit — otherwise an unrelated failure would be masked by some
            // other key that merely happens to be cooling down.
            waitMs != null && (raw == null || lastErrorWasRateLimit) ->
                context.getString(R.string.toast_key_rate_limited, ((waitMs + 999) / 1000).coerceAtLeast(1))
            // Must precede the generic branch: raw is never null once a request was attempted. A
            // 403 is usually the selected model not being available to the project rather than
            // bad keys, so don't send the user off to check keys that are fine.
            lastErrorWasPermission -> context.getString(R.string.error_no_model_access)
            raw != null -> {
                val mapped = ErrorMessages.map(raw)
                if (mapped == R.string.error_invalid_key && failedKey != null && keyManager.getKeys(providerType).size > 1) {
                    context.getString(R.string.error_invalid_key_with_hint, "••••" + failedKey.takeLast(4))
                } else {
                    context.getString(mapped)
                }
            }
            keyManager.getKeys(providerType).isEmpty() -> context.getString(R.string.toast_no_keys)
            else -> context.getString(R.string.toast_all_keys_invalid)
        }
    )
}
