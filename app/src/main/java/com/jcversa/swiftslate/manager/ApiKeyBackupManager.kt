package com.jcversa.swiftslate.manager

import android.content.Context
import android.util.Base64
import com.jcversa.swiftslate.model.PrefKeys
import com.jcversa.swiftslate.model.ProviderType
import com.jcversa.swiftslate.provider.EndpointValidator
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Portable, password-encrypted export/import for API keys and the selected model per provider.
 *
 * The normal Android backup deliberately excludes the key store. This class is the explicit
 * escape hatch: the user chooses a passphrase, and only an authenticated AES-GCM envelope leaves
 * the app. The passphrase is never persisted, sent over the network, or copied to the clipboard.
 */
class ApiKeyBackupManager(
    context: Context,
    private val keyManager: KeyManager
) {
    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        const val FORMAT = "swiftslate-encrypted-api-keys"
        const val VERSION = 1
        const val FILE_EXTENSION = "json"
        const val MIN_PASSPHRASE_LENGTH = 8

        private const val KDF = "PBKDF2"
        private const val PRF_SHA256 = "HmacSHA256"
        private const val PRF_SHA1 = "HmacSHA1"
        private const val PBKDF2_SHA256 = "PBKDF2WithHmacSHA256"
        private const val PBKDF2_SHA1 = "PBKDF2WithHmacSHA1"
        private const val CIPHER = "AES-256-GCM"
        private const val ITERATIONS_SHA256 = 210_000
        private const val ITERATIONS_SHA1 = 310_000
        private const val KEY_BITS = 256
        private const val SALT_BYTES = 16
        private const val IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MAX_FILE_BYTES = 1_000_000
        private const val MAX_KEYS_PER_PROVIDER = 100
        private const val MAX_MODEL_LENGTH = 256
        private const val MAX_ENDPOINT_LENGTH = 2_048

        private val modelPrefs = mapOf(
            ProviderType.GEMINI to PrefKeys.GEMINI_MODEL,
            ProviderType.GROQ to PrefKeys.GROQ_MODEL,
            ProviderType.NVIDIA to PrefKeys.NVIDIA_MODEL,
            ProviderType.OPENROUTER to PrefKeys.OPENROUTER_MODEL,
            ProviderType.DEEPSEEK to PrefKeys.DEEPSEEK_MODEL,
            ProviderType.CUSTOM to PrefKeys.CUSTOM_MODEL
        )

        /** Returns the providers supported by this app version, in a stable file order. */
        internal val supportedProviders: List<String> = ProviderType.ALL

        /** Password policy is intentionally local and deterministic for the UI and tests. */
        fun isPassphraseAcceptable(passphrase: CharSequence): Boolean =
            passphrase.length >= MIN_PASSPHRASE_LENGTH

        internal fun modelPrefKey(provider: String): String? = modelPrefs[provider]
    }

    data class ImportedData(
        val activeProvider: String,
        val keysByProvider: Map<String, List<String>>,
        val modelsByProvider: Map<String, String>,
        val customEndpoint: String
    ) {
        val keyCount: Int get() = keysByProvider.values.sumOf { it.size }
        val configuredProviderCount: Int get() = keysByProvider.count { it.value.isNotEmpty() }
    }

    data class ImportSummary(
        val configuredProviderCount: Int,
        val keyCount: Int,
        val modelsIncluded: Int,
        val includesCustomEndpoint: Boolean
    )

    /**
     * Builds the encrypted JSON file. Key decryption and encryption happen on the caller's IO
     * dispatcher; this method deliberately has no network path.
     */
    fun export(passphrase: CharSequence): String {
        require(isPassphraseAcceptable(passphrase)) { "Passphrase is too short" }
        check(keyManager.keystoreAvailable) { "Keystore unavailable" }

        val payload = JSONObject().apply {
            put("active_provider", settings.getString(PrefKeys.PROVIDER_TYPE, ProviderType.GEMINI))
            put("keys", JSONObject().apply {
                supportedProviders.forEach { provider ->
                    put(provider, JSONArray(keyManager.getKeys(provider)))
                }
            })
            put("models", JSONObject().apply {
                supportedProviders.forEach { provider ->
                    put(provider, settings.getString(modelPrefs.getValue(provider), "").orEmpty())
                }
            })
            // A custom endpoint is configuration, not a secret. It is included only in this
            // explicit encrypted export, never in the standard backup envelope.
            put("custom_endpoint", settings.getString(PrefKeys.CUSTOM_ENDPOINT, "").orEmpty())
        }

        val salt = ByteArray(SALT_BYTES)
        val iv = ByteArray(IV_BYTES)
        SecureRandom().nextBytes(salt)
        SecureRandom().nextBytes(iv)
        val derived = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derived.key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(payload.toString().toByteArray(StandardCharsets.UTF_8))

        return JSONObject().apply {
            put("format", FORMAT)
            put("version", VERSION)
            put("kdf", JSONObject().apply {
                put("name", KDF)
                put("prf", derived.prf)
                put("iterations", derived.iterations)
                put("salt", encode(salt))
                put("key_bits", KEY_BITS)
            })
            put("cipher", JSONObject().apply {
                put("name", CIPHER)
                put("iv", encode(iv))
                put("tag_bits", GCM_TAG_BITS)
            })
            put("ciphertext", encode(ciphertext))
        }.toString(2)
    }

    /**
     * Decrypts and validates a file without applying it. The UI can show the summary and ask for
     * a final confirmation before replacing existing provider keys.
     */
    fun inspect(encryptedFile: String, passphrase: CharSequence): Pair<ImportedData, ImportSummary> {
        require(encryptedFile.toByteArray(StandardCharsets.UTF_8).size <= MAX_FILE_BYTES) {
            "Backup file is too large"
        }
        require(isPassphraseAcceptable(passphrase)) { "Passphrase is too short" }
        val root = JSONObject(encryptedFile)
        require(root.optString("format") == FORMAT) { "Unsupported backup format" }
        require(root.optInt("version", -1) == VERSION) { "Unsupported backup version" }

        val kdf = root.getJSONObject("kdf")
        require(kdf.optString("name") == KDF) { "Unsupported key derivation" }
        val prf = kdf.optString("prf")
        require(prf == PRF_SHA256 || prf == PRF_SHA1) { "Unsupported key derivation" }
        val iterations = kdf.optInt("iterations", 0)
        val expectedIterations = if (prf == PRF_SHA256) ITERATIONS_SHA256 else ITERATIONS_SHA1
        require(iterations == expectedIterations) { "Unsupported key derivation cost" }
        val salt = decode(kdf.getString("salt"), SALT_BYTES)
        require(kdf.optInt("key_bits", 0) == KEY_BITS) { "Unsupported key size" }

        val cipherInfo = root.getJSONObject("cipher")
        require(cipherInfo.optString("name") == CIPHER) { "Unsupported encryption" }
        require(cipherInfo.optInt("tag_bits", 0) == GCM_TAG_BITS) { "Unsupported encryption" }
        val iv = decode(cipherInfo.getString("iv"), IV_BYTES)
        val ciphertext = decode(root.getString("ciphertext"), 16)
        val derived = deriveKey(passphrase, salt, prf, iterations)
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derived.key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        } catch (error: Exception) {
            throw IllegalArgumentException("Wrong passphrase or damaged backup", error)
        }

        val data = parsePayload(JSONObject(plain))
        return data to ImportSummary(
            configuredProviderCount = data.configuredProviderCount,
            keyCount = data.keyCount,
            modelsIncluded = data.modelsByProvider.count { it.value.isNotBlank() },
            includesCustomEndpoint = data.customEndpoint.isNotBlank()
        )
    }

    /** Replaces all provider key namespaces in one SharedPreferences transaction. */
    fun apply(data: ImportedData): Boolean {
        val keyMap = data.keysByProvider
        if (keyMap.keys.any { it !in supportedProviders }) return false
        if (data.activeProvider !in supportedProviders) return false
        if (!keyManager.replaceAllKeys(keyMap)) return false
        return try {
            val editor = settings.edit()
            editor.putString(PrefKeys.PROVIDER_TYPE, data.activeProvider)
            data.modelsByProvider.forEach { (provider, model) ->
                modelPrefKey(provider)?.let { editor.putString(it, model) }
            }
            val endpoint = data.customEndpoint
            if (endpoint.isBlank() || EndpointValidator.validate(endpoint) == EndpointValidator.Error.NONE) {
                editor.putString(PrefKeys.CUSTOM_ENDPOINT, endpoint)
            } else {
                return false
            }
            editor.commit()
        } catch (_: Exception) {
            false
        }
    }

    private data class DerivedKey(
        val key: SecretKeySpec,
        val prf: String,
        val iterations: Int
    )

    private fun deriveKey(
        passphrase: CharSequence,
        salt: ByteArray,
        requestedPrf: String? = null,
        requestedIterations: Int? = null
    ): DerivedKey {
        val prf = requestedPrf ?: try {
            SecretKeyFactory.getInstance(PBKDF2_SHA256)
            PRF_SHA256
        } catch (_: Exception) {
            PRF_SHA1
        }
        val iterations = requestedIterations ?: if (prf == PRF_SHA256) ITERATIONS_SHA256 else ITERATIONS_SHA1
        val factoryAlgorithm = if (prf == PRF_SHA256) PBKDF2_SHA256 else PBKDF2_SHA1
        val spec = PBEKeySpec(passphrase.toString().toCharArray(), salt, iterations, KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(factoryAlgorithm)
            val bytes = factory.generateSecret(spec).encoded
            DerivedKey(SecretKeySpec(bytes, "AES"), prf, iterations)
        } finally {
            spec.clearPassword()
        }
    }

    private fun parsePayload(payload: JSONObject): ImportedData {
        val active = payload.optString("active_provider")
        require(active in supportedProviders) { "Unknown active provider" }
        val keyObject = payload.optJSONObject("keys") ?: error("Missing key data")
        val keys = linkedMapOf<String, List<String>>()
        supportedProviders.forEach { provider ->
            val array = keyObject.optJSONArray(provider) ?: error("Missing provider key data")
            require(array.length() <= MAX_KEYS_PER_PROVIDER) { "Too many keys" }
            val values = buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index, "")
                    require(value.isNotBlank() && value.length <= 256) { "Invalid API key" }
                    add(value)
                }
            }.distinct()
            keys[provider] = values
        }

        val modelObject = payload.optJSONObject("models") ?: error("Missing model data")
        val models = linkedMapOf<String, String>()
        supportedProviders.forEach { provider ->
            val model = modelObject.optString(provider, "")
            require(model.length <= MAX_MODEL_LENGTH) { "Invalid model" }
            models[provider] = model
        }
        val endpoint = payload.optString("custom_endpoint", "")
        require(endpoint.length <= MAX_ENDPOINT_LENGTH) { "Invalid endpoint" }
        if (endpoint.isNotBlank()) {
            require(EndpointValidator.validate(endpoint) == EndpointValidator.Error.NONE) {
                "Invalid endpoint"
            }
        }
        return ImportedData(active, keys, models, endpoint)
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(value: String, minBytes: Int): ByteArray {
        val bytes = try { Base64.decode(value, Base64.NO_WRAP) } catch (_: Exception) {
            error("Invalid encrypted backup")
        }
        require(bytes.size >= minBytes) { "Invalid encrypted backup" }
        return bytes
    }
}
