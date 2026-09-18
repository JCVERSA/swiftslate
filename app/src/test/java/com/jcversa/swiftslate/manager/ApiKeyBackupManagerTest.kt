package com.jcversa.swiftslate.manager

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jcversa.swiftslate.model.PrefKeys
import com.jcversa.swiftslate.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private class BackupFakeCipher(override val available: Boolean = true) : KeyCipher {
    override fun encrypt(plainText: String): String =
        "fake]" + java.util.Base64.getEncoder().encodeToString(plainText.toByteArray())

    override fun decrypt(encrypted: String): String? = encrypted.split("]").takeIf { it.size == 2 }
        ?.getOrNull(1)
        ?.let { encoded ->
            try { String(java.util.Base64.getDecoder().decode(encoded)) } catch (_: Exception) { null }
        }
}

@RunWith(RobolectricTestRunner::class)
class ApiKeyBackupManagerTest {
    private lateinit var keyManager: KeyManager
    private lateinit var backup: ApiKeyBackupManager
    private lateinit var settings: android.content.SharedPreferences

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("secure_keys_prefs", 0).edit().clear().commit()
        settings = context.getSharedPreferences("settings", 0)
        settings.edit().clear().commit()
        keyManager = KeyManager(context, BackupFakeCipher())
        backup = ApiKeyBackupManager(context, keyManager)
    }

    @Test
    fun exportContainsNoPlaintextKeysAndRestoresSelectedModels() {
        keyManager.addKey("gemini-secret", ProviderType.GEMINI)
        keyManager.addKey("groq-secret", ProviderType.GROQ)
        settings.edit()
            .putString(PrefKeys.PROVIDER_TYPE, ProviderType.GROQ)
            .putString(PrefKeys.GEMINI_MODEL, "gemini-2.5-flash")
            .putString(PrefKeys.GROQ_MODEL, "llama-3.3-70b-versatile")
            .putString(PrefKeys.CUSTOM_ENDPOINT, "https://example.com/v1")
            .apply()

        val encrypted = backup.export("correct horse battery staple")
        assertFalse(encrypted.contains("gemini-secret"))
        assertFalse(encrypted.contains("groq-secret"))

        val (data, summary) = backup.inspect(encrypted, "correct horse battery staple")
        assertEquals(2, summary.keyCount)
        assertEquals(2, summary.configuredProviderCount)
        assertEquals(ProviderType.GROQ, data.activeProvider)
        assertEquals("gemini-2.5-flash", data.modelsByProvider[ProviderType.GEMINI])
        assertEquals("https://example.com/v1", data.customEndpoint)
    }

    @Test(expected = IllegalArgumentException::class)
    fun wrongPassphraseIsRejectedBeforeApply() {
        keyManager.addKey("secret", ProviderType.GEMINI)
        val encrypted = backup.export("correct horse battery staple")
        backup.inspect(encrypted, "wrong passphrase")
    }

    @Test
    fun applyReplacesAllProviderKeysWithoutNetworkValidation() {
        keyManager.addKey("old", ProviderType.GEMINI)
        val encrypted = backup.export("correct horse battery staple")
        val (original, _) = backup.inspect(encrypted, "correct horse battery staple")
        val replacement = original.copy(
            activeProvider = ProviderType.GEMINI,
            keysByProvider = ApiKeyBackupManager.supportedProviders.associateWith { provider ->
                if (provider == ProviderType.GEMINI) listOf("new-secret") else emptyList()
            },
            modelsByProvider = ApiKeyBackupManager.supportedProviders.associateWith { "selected-model" },
            customEndpoint = ""
        )

        assertTrue(backup.apply(replacement))
        assertEquals(listOf("new-secret"), keyManager.getKeys(ProviderType.GEMINI))
        assertTrue(keyManager.getKeys(ProviderType.GROQ).isEmpty())
        assertEquals("selected-model", settings.getString(PrefKeys.GROQ_MODEL, null))
    }

    @Test
    fun shortPassphrasesAreRejected() {
        assertFalse(ApiKeyBackupManager.isPassphraseAcceptable("short"))
        assertTrue(ApiKeyBackupManager.isPassphraseAcceptable("long enough"))
    }
}
