package com.jcversa.swiftslate.manager

import android.content.Context
import android.content.SharedPreferences
import com.jcversa.swiftslate.model.ProviderType
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encrypted API-key storage and per-provider key rotation.
 *
 * Keys are stored in separate encrypted preference entries for each provider. The old
 * single `keys_array` entry is migrated lazily to the provider that is active when it is
 * first read. This keeps existing installs usable while preventing a Gemini key from being
 * selected for Groq or a custom endpoint after a provider switch.
 */
class KeyManager internal constructor(
    context: Context,
    private val cipher: KeyCipher
) {
    constructor(context: Context) : this(context, AndroidKeystoreCipher())

    private val prefs: SharedPreferences =
        context.getSharedPreferences("secure_keys_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val LEGACY_PREF_KEY_ARRAY = "keys_array"
        private const val PREF_KEY_PREFIX = "keys_array_"
        private const val INVALID_PROVIDER = "__invalid_provider__"
        private const val CACHE_TTL_MS = 5_000L
        private const val MAX_KEY_LENGTH = 256
        // Invalid-key marks expire. A 403 is not always the key's fault (e.g. selecting a
        // model the key's project can't access returns 403 for every key), and marks used
        // to last for the whole process lifetime — so one bad model choice permanently
        // killed every key with no recovery except re-adding them all.
        private const val INVALID_KEY_TTL_MS = 900_000L // 15 min

        /**
         * Whether [stored] is a pre-encryption plaintext JSON array rather than ciphertext.
         *
         * This used to be `!stored.contains("]")`, which is wrong: the legacy format is a JSON
         * array, and `["key"]` contains that separator. Legacy values were therefore routed
         * straight to decrypt(), which split them on "]", failed, and made getKeys() return —
         * and cache — an empty list. Anyone upgrading from a plaintext build silently lost every
         * key. Ciphertext is "<base64>]<base64>" and base64 never starts with "[", so the two
         * shapes are unambiguous.
         */
        internal fun isLegacyPlaintext(stored: String): Boolean = stored.trimStart().startsWith("[")
    }

    private val rateLimitedKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** provider + key -> timestamp after which the invalid mark is forgotten. */
    private val invalidKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val roundRobinIndex = AtomicInteger(0)
    @Volatile
    private var cachedKeys: List<String>? = null
    @Volatile
    private var cachedProvider: String? = null
    @Volatile
    private var cachedStorageKey: String? = null
    @Volatile
    private var cacheTimestamp = 0L
    /**
     * Ciphertext [cachedKeys] was decrypted from, so an expired TTL can be revalidated with a
     * string compare instead of another AndroidKeyStore round trip + AES-GCM decrypt. The TTL
     * used to be what let the UI's instance notice the accessibility service's writes; every
     * caller now shares [com.jcversa.swiftslate.SwiftSlateApp.keyManager], so it is only a
     * backstop against prefs changing underneath a single instance.
     */
    @Volatile
    private var cachedCipherText: String? = null

    val keystoreAvailable: Boolean get() = cipher.available

    private fun providerOf(provider: String?): String =
        if (provider == null) ProviderType.GEMINI
        else ProviderType.storedOrNull(provider) ?: INVALID_PROVIDER

    private fun isInvalidProvider(provider: String): Boolean = provider == INVALID_PROVIDER

    private fun storageKey(provider: String): String = PREF_KEY_PREFIX + provider

    /**
     * SharedPreferences throws ClassCastException when a value was corrupted or written with a
     * different type. Treat that value as invalid and remove only the affected entry rather than
     * allowing a UI/service read to crash the process.
     */
    private fun readStringSafely(key: String): String? = try {
        prefs.getString(key, null)
    } catch (_: ClassCastException) {
        try { prefs.edit().remove(key).apply() } catch (_: Exception) { }
        null
    }

    private fun writeString(key: String, value: String): Boolean = try {
        // This method is called from the IO dispatcher by UI/service code. commit gives migration
        // a durable success signal so the legacy value is not removed before the scoped copy exists.
        prefs.edit().putString(key, value).commit()
    } catch (_: Exception) {
        false
    }

    private fun removeEntry(key: String) {
        try { prefs.edit().remove(key).apply() } catch (_: Exception) { }
    }

    private fun namespaced(provider: String, key: String): String = "$provider\u0000$key"

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }

    private fun parseKeys(json: String): List<String> = try {
        JSONArray(json).toStringList()
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun getKeys(providerType: String = ProviderType.GEMINI): List<String> {
        val provider = providerOf(providerType)
        if (isInvalidProvider(provider)) return emptyList()
        val now = System.currentTimeMillis()
        val cached = cachedKeys
        if (cached != null && cachedProvider == provider && now - cacheTimestamp < CACHE_TTL_MS) {
            return cached
        }

        val scopedKey = storageKey(provider)
        val scopedStored = readStringSafely(scopedKey)
        val sourceKey: String
        val stored: String?
        if (scopedStored != null) {
            stored = scopedStored
            sourceKey = scopedKey
        } else {
            // One-time compatibility path for installs written before provider-scoped storage.
            stored = readStringSafely(LEGACY_PREF_KEY_ARRAY)
            sourceKey = LEGACY_PREF_KEY_ARRAY
        }
        if (stored == null) {
            cacheKeys(emptyList(), null, provider, scopedKey)
            return emptyList()
        }

        // TTL expired but the stored ciphertext is byte-identical, so the plaintext cannot have
        // changed: revalidate the cache rather than paying for another KeyStore decrypt.
        if (cached != null && cachedProvider == provider &&
            cachedStorageKey == sourceKey && stored == cachedCipherText
        ) {
            cacheTimestamp = now
            return cached
        }

        // Legacy plaintext migration — can be removed once all users are on an encrypted build.
        if (isLegacyPlaintext(stored)) {
            val list = try { JSONArray(stored).toStringList() } catch (_: Exception) { emptyList() }
            return try {
                val encrypted = cipher.encrypt(stored)
                if (writeString(scopedKey, encrypted)) {
                    if (sourceKey != scopedKey) removeEntry(sourceKey)
                    cacheKeys(list, encrypted, provider, scopedKey)
                }
                // If encryption fails, keep the old value and return it without caching. This
                // preserves access to a legacy install while the Keystore is unavailable.
                list
            } catch (_: Exception) {
                list
            }
        }

        val jsonStr = cipher.decrypt(stored) ?: run {
            cacheKeys(emptyList(), stored, provider, sourceKey)
            return emptyList()
        }
        val list = parseKeys(jsonStr)
        if (sourceKey != scopedKey && writeString(scopedKey, stored)) {
            removeEntry(sourceKey)
            cacheKeys(list, stored, provider, scopedKey)
        } else {
            cacheKeys(list, stored, provider, sourceKey)
        }
        return list
    }

    private fun cacheKeys(keys: List<String>, cipherText: String?, provider: String, storageKey: String) {
        cachedKeys = keys
        cachedCipherText = cipherText
        cachedProvider = provider
        cachedStorageKey = storageKey
        cacheTimestamp = System.currentTimeMillis()
    }

    private fun invalidateCache() {
        cachedKeys = null
        cachedCipherText = null
        cachedProvider = null
        cachedStorageKey = null
        cacheTimestamp = 0L
    }

    @Synchronized
    private fun saveKeys(providerType: String, keys: List<String>): Boolean {
        val provider = providerOf(providerType)
        if (isInvalidProvider(provider)) return false
        val arr = JSONArray(keys)
        return try {
            val cipherText = cipher.encrypt(arr.toString())
            val key = storageKey(provider)
            if (!writeString(key, cipherText)) {
                invalidateCache()
                false
            } else {
                cacheKeys(keys, cipherText, provider, key)
                true
            }
        } catch (_: Exception) {
            // Invalidate: the stored value and the in-memory list may now disagree.
            invalidateCache()
            false
        }
    }

    /**
     * Replaces every provider namespace in one durable edit. Secure imports prepare and encrypt
     * every provider before this method is called, so a failed Keystore operation cannot leave a
     * half-imported set of keys.
     */
    @Synchronized
    fun replaceAllKeys(keysByProvider: Map<String, List<String>>): Boolean {
        if (keysByProvider.keys.any { it !in ProviderType.ALL }) return false
        if (keysByProvider.keys != ProviderType.ALL.toSet()) return false
        if (keysByProvider.values.any { values ->
                values.size > 100 || values.distinct().size != values.size ||
                    values.any { it.isBlank() || it.length > MAX_KEY_LENGTH }
            }) return false

        return try {
            val encrypted = keysByProvider.mapValues { (_, keys) ->
                cipher.encrypt(JSONArray(keys).toString())
            }
            val editor = prefs.edit()
            encrypted.forEach { (provider, value) ->
                editor.putString(storageKey(provider), value)
            }
            editor.remove(LEGACY_PREF_KEY_ARRAY)
            if (!editor.commit()) {
                invalidateCache()
                false
            } else {
                invalidateCache()
                true
            }
        } catch (_: Exception) {
            invalidateCache()
            false
        }
    }

    @Synchronized
    fun addKey(key: String, providerType: String = ProviderType.GEMINI): Boolean {
        if (key.isBlank() || key.length > MAX_KEY_LENGTH) return false
        val provider = providerOf(providerType)
        if (isInvalidProvider(provider)) return false
        val keys = getKeys(provider).toMutableList()
        if (!keys.contains(key)) {
            keys.add(key)
            if (!saveKeys(provider, keys)) return false
        }
        clearMarks(key, provider)
        return true
    }

    @Synchronized
    fun removeKey(key: String, providerType: String = ProviderType.GEMINI): Boolean {
        val provider = providerOf(providerType)
        if (isInvalidProvider(provider)) return false
        val keys = getKeys(provider).toMutableList()
        keys.remove(key)
        val saved = saveKeys(provider, keys)
        rateLimitedKeys.remove(namespaced(provider, key))
        invalidKeys.remove(namespaced(provider, key))
        return saved
    }

    // All @Synchronized methods use `this` as monitor (reentrant).
    // getNextKey() intentionally calls getKeys() while holding the lock.
    /**
     * Next usable key, skipping benched ones and anything in [alreadyTried].
     *
     * [alreadyTried] exists because a monotonic round-robin index modulo a *shrinking* list is
     * not a permutation: with keys [A,B,C] and the counter at 1, a 5xx on B followed by a 429 on
     * C mapped attempt 3 back to B — re-sending a byte-identical request while A was never tried
     * at all, so the command could fail with a healthy key sitting idle.
     */
    @Synchronized
    fun getNextKey(
        alreadyTried: Set<String> = emptySet(),
        providerType: String = ProviderType.GEMINI
    ): String? {
        val provider = providerOf(providerType)
        if (isInvalidProvider(provider)) return null
        val keys = getKeys(provider)
        if (keys.isEmpty()) return null

        val now = System.currentTimeMillis()
        val validKeys = keys.filter { key ->
            if (key in alreadyTried) return@filter false
            val scoped = namespaced(provider, key)
            if (isInvalid(scoped)) return@filter false
            val limitTime = rateLimitedKeys[scoped] ?: 0L
            now > limitTime
        }

        if (validKeys.isEmpty()) return null

        val idx = (roundRobinIndex.getAndIncrement() and Int.MAX_VALUE) % validKeys.size
        return validKeys[idx]
    }

    fun reportRateLimit(
        key: String,
        retryAfterSeconds: Long = 60,
        providerType: String = ProviderType.GEMINI
    ) {
        val provider = providerOf(providerType)
        if (isInvalidProvider(provider)) return
        val cooldown = retryAfterSeconds.coerceIn(1, 600)
        rateLimitedKeys[namespaced(provider, key)] =
            System.currentTimeMillis() + cooldown * 1_000
    }

    fun markInvalid(key: String, providerType: String = ProviderType.GEMINI) {
        val provider = providerOf(providerType)
        if (isInvalidProvider(provider)) return
        invalidKeys[namespaced(provider, key)] =
            System.currentTimeMillis() + INVALID_KEY_TTL_MS
    }

    /**
     * Clears any in-memory invalid/rate-limit marks for [key] so it is usable again on the
     * next attempt. Called when the user re-adds a key: previously the UI's "already added"
     * early-return skipped [addKey]'s un-benching, leaving the accessibility service benching
     * the key for the full 15-minute TTL even after the user fixed it.
     */
    @Synchronized
    fun clearMarks(key: String, providerType: String = ProviderType.GEMINI) {
        val provider = providerOf(providerType)
        if (isInvalidProvider(provider)) return
        val scoped = namespaced(provider, key)
        invalidKeys.remove(scoped)
        rateLimitedKeys.remove(scoped)
    }

    /**
     * Whether [key] is currently benched as invalid, expiring the mark if it is due.
     * Self-healing: without expiry a transient 403 killed the key until the process
     * restarted (see [INVALID_KEY_TTL_MS]).
     */
    private fun isInvalid(scopedKey: String): Boolean {
        val until = invalidKeys[scopedKey] ?: return false
        if (System.currentTimeMillis() >= until) {
            invalidKeys.remove(scopedKey)
            return false
        }
        return true
    }

    fun getShortestWaitTimeMs(providerType: String = ProviderType.GEMINI): Long? {
        val provider = providerOf(providerType)
        if (isInvalidProvider(provider)) return null
        val keys = getKeys(provider)
        if (keys.isEmpty()) return null
        val now = System.currentTimeMillis()
        val waits = keys.filter { !isInvalid(namespaced(provider, it)) }
            .mapNotNull { key ->
                val limitTime = rateLimitedKeys[namespaced(provider, key)] ?: return@mapNotNull null
                val remaining = limitTime - now
                if (remaining > 0) remaining else null
            }
        return waits.minOrNull()
    }
}
