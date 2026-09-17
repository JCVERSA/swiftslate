package com.jcversa.swiftslate.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcversa.swiftslate.R
import com.jcversa.swiftslate.api.ApiClientUtils
import com.jcversa.swiftslate.api.GeminiClient
import com.jcversa.swiftslate.api.OpenAICompatibleClient
import com.jcversa.swiftslate.manager.KeyManager
import com.jcversa.swiftslate.model.PrefKeys
import com.jcversa.swiftslate.model.ProviderType
import com.jcversa.swiftslate.provider.DeepSeekConfig
import com.jcversa.swiftslate.provider.GroqConfig
import com.jcversa.swiftslate.provider.NvidiaConfig
import com.jcversa.swiftslate.provider.OpenRouterConfig
import com.jcversa.swiftslate.ui.components.LocalSlateRhythm
import com.jcversa.swiftslate.ui.components.SlateCard
import com.jcversa.swiftslate.ui.components.SlateItemCard
import com.jcversa.swiftslate.ui.components.SlateTextField
import com.jcversa.swiftslate.ui.components.AnimateEntrance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun KeysScreen(keyManager: KeyManager, prefs: SharedPreferences) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    // Deliberately not seeded from keyManager.getKeys(): that decrypts through AndroidKeyStore
    // (and on a legacy store also does a synchronous prefs commit), which ran on the main thread
    // during composition. Loaded in the LaunchedEffect below instead.
    var keys by remember { mutableStateOf<List<String>>(emptyList()) }
    var keyToDelete by remember { mutableStateOf<String?>(null) }
    // Plain remember, not rememberSaveable: a pasted key the user has not submitted yet is
    // still a live secret and must not be written to system-managed saved-instance state.
    var newKey by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testSuccess by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val geminiClient = remember { GeminiClient() }
    val openAIClient = remember { OpenAICompatibleClient() }

    // Read on every recomposition: the Settings tab can change the active provider while this
    // movable screen is kept alive by MainActivity's tab container.
    val storedProviderType = prefs.getString(PrefKeys.PROVIDER_TYPE, null)
    val providerConfigurationInvalid =
        storedProviderType != null && !ProviderType.isValid(storedProviderType)
    val providerType = ProviderType.storedOrNull(storedProviderType) ?: ProviderType.GEMINI

    LaunchedEffect(providerType, providerConfigurationInvalid) {
        keys = if (providerConfigurationInvalid) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { keyManager.getKeys(providerType) }
        }
    }

    val validAddedMsg = stringResource(R.string.keys_valid_added)
    val alreadyAddedMsg = stringResource(R.string.keys_already_added)
    val validationFailedMsg = stringResource(R.string.keys_validation_failed)
    val keystoreErrorMsg = stringResource(R.string.keys_keystore_error)
    val customEndpointRequiredMsg = stringResource(R.string.keys_custom_endpoint_required)
    val signinRequiredMsg = stringResource(R.string.error_provider_auth_required)
    val endpointNeedsV1Msg = stringResource(R.string.keys_endpoint_needs_v1)
    val rhythm = LocalSlateRhythm.current

    // Provider display names stay literals: proper nouns, like the pre-redesign "Groq"/"Gemini".
    val providerName = if (providerConfigurationInvalid) {
        stringResource(R.string.error_provider_selection_invalid)
    } else {
        when (providerType) {
            ProviderType.GROQ -> "Groq AI"
            ProviderType.NVIDIA -> "NVIDIA NIM"
            ProviderType.OPENROUTER -> "OpenRouter"
            ProviderType.DEEPSEEK -> "DeepSeek"
            ProviderType.CUSTOM -> "Custom OpenAI Provider"
            else -> "Google Gemini AI"
        }
    }

    val apiKeyUrl = if (providerConfigurationInvalid) {
        null
    } else {
        when (providerType) {
            ProviderType.GROQ -> "https://console.groq.com/keys"
            ProviderType.NVIDIA -> "https://build.nvidia.com/settings/api-keys"
            ProviderType.OPENROUTER -> "https://openrouter.ai/settings/keys"
            ProviderType.DEEPSEEK -> "https://platform.deepseek.com/api_keys"
            ProviderType.CUSTOM -> null
            else -> "https://aistudio.google.com/api-keys"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = rhythm.screenPaddingH, vertical = rhythm.screenPaddingV)
    ) {
        // Redesigned Top Header Row
        AnimateEntrance(index = 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = rhythm.cardGap),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.keys_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.keys_subtitle),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (providerConfigurationInvalid) {
            SlateCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.error_provider_selection_invalid),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(modifier = Modifier.height(rhythm.cardGap))
        }

        if (!keyManager.keystoreAvailable) {
            SlateCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(2.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = keystoreErrorMsg,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
            Spacer(modifier = Modifier.height(rhythm.cardGap))
        }

        // Key Input Card
        AnimateEntrance(index = 1) {
            SlateCard {
                Column(modifier = Modifier.padding(2.dp)) {
                // Header of Section
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VpnKey,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.keys_register_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // Active Provider Badge
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        )
                    ) {
                        Text(
                            text = providerName.uppercase(),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                SlateTextField(
                    value = newKey,
                    onValueChange = { if (it.length <= 256) newKey = it },
                    placeholder = { Text(stringResource(R.string.keys_api_key_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        if (newKey.isNotBlank()) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            isTesting = true
                            testResult = null
                            scope.launch {
                                val trimmedKey = newKey.trim()
                                if (withContext(Dispatchers.IO) { keyManager.getKeys(providerType) }.contains(trimmedKey)) {
                                    isTesting = false
                                    // Re-adding an existing key means the user is retrying it after a
                                    // failure — clear any invalid/rate-limit bench so the service can
                                    // use it again immediately instead of waiting out the 15-min TTL.
                                    withContext(Dispatchers.IO) { keyManager.clearMarks(trimmedKey, providerType) }
                                    testResult = alreadyAddedMsg
                                    testSuccess = false
                                    return@launch
                                }
                                val result = run {
                                    val customEndpoint = (prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: "").trim()
                                    when {
                                        providerType == ProviderType.CUSTOM && customEndpoint.isBlank() -> {
                                            isTesting = false
                                            testResult = customEndpointRequiredMsg
                                            testSuccess = false
                                            return@launch
                                        }
                                        providerType == ProviderType.GROQ ->
                                            openAIClient.validateKey(trimmedKey, GroqConfig.ENDPOINT)
                                        providerType == ProviderType.NVIDIA ->
                                            openAIClient.validateKey(trimmedKey, NvidiaConfig.ENDPOINT)
                                        providerType == ProviderType.OPENROUTER ->
                                            openAIClient.validateKey(trimmedKey, OpenRouterConfig.ENDPOINT)
                                        providerType == ProviderType.DEEPSEEK ->
                                            openAIClient.validateKey(trimmedKey, DeepSeekConfig.ENDPOINT)
                                        providerType == ProviderType.CUSTOM ->
                                            openAIClient.validateKey(trimmedKey, customEndpoint)
                                        else ->
                                            geminiClient.validateKey(trimmedKey)
                                    }
                                }
                                isTesting = false
                                if (result.isSuccess) {
                                    if (!withContext(Dispatchers.IO) { keyManager.addKey(trimmedKey, providerType) }) {
                                        testResult = keystoreErrorMsg
                                        testSuccess = false
                                        return@launch
                                    }
                                    keys = withContext(Dispatchers.IO) { keyManager.getKeys(providerType) }
                                    newKey = ""
                                    testResult = validAddedMsg
                                    testSuccess = true
                                    // Clear clipboard to prevent API key leaking via paste history
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                                } else {
                                    val raw = result.exceptionOrNull()?.message ?: ""
                                    testResult = when {
                                        raw.contains(ApiClientUtils.SIGNIN_REQUIRED_MARKER) -> signinRequiredMsg
                                        raw.contains(ApiClientUtils.NEEDS_V1_MARKER) -> endpointNeedsV1Msg
                                        else -> ApiClientUtils.redactSecrets(
                                            ApiClientUtils.redactSubmittedKey(raw, trimmedKey)
                                        ).ifEmpty { validationFailedMsg }
                                    }
                                    testSuccess = false
                                }
                            }
                        }
                    },
                    enabled = newKey.isNotBlank() && !isTesting && keyManager.keystoreAvailable &&
                        !providerConfigurationInvalid,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    if (isTesting) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(stringResource(R.string.keys_testing), fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(stringResource(R.string.keys_add_key), fontWeight = FontWeight.Bold)
                    }
                }

                // Smoothly animated result banners
                AnimatedVisibility(
                    visible = testResult != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    testResult?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (testSuccess) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                                    else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (testSuccess) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                                else MaterialTheme.colorScheme.error.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (testSuccess) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                                    contentDescription = null,
                                    tint = if (testSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = msg,
                                    color = if (testSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                if (apiKeyUrl != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        onClick = { uriHandler.openUri(apiKeyUrl) },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.VpnKey,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = stringResource(R.string.keys_get_api_key, providerName),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Active Keys List
        AnimateEntrance(index = 2) {
            if (keys.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxHeight()) {
                    Text(
                        text = stringResource(R.string.keys_list_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    SlateCard(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(12.dp)),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    itemsIndexed(keys, key = { index, k -> "$index-${k.hashCode()}" }) { index, key ->
                        SlateItemCard {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Pulsing/Glowing Active Dot on key icon container
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    // Status light
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(MaterialTheme.colorScheme.tertiary, CircleShape)
                                            .align(Alignment.TopEnd)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                                    Text(
                                        text = "•••• •••• •••• " + key.takeLast(4),
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = stringResource(R.string.keys_encrypted_note),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    keyToDelete = key
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeleteOutline,
                                    contentDescription = stringResource(R.string.delete_confirm_button),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        SlateCard(modifier = Modifier.fillMaxHeight()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.LockOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.keys_empty),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
    }

    keyToDelete?.let { keyValue ->
        AlertDialog(
            onDismissRequest = { keyToDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_key_title)) },
            text = { Text(stringResource(R.string.delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    keyToDelete = null
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) { keyManager.removeKey(keyValue, providerType) }
                        if (removed) {
                            keys = withContext(Dispatchers.IO) { keyManager.getKeys(providerType) }
                        } else {
                            testResult = keystoreErrorMsg
                            testSuccess = false
                        }
                    }
                }) {
                    Text(stringResource(R.string.delete_confirm_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { keyToDelete = null }) {
                    Text(stringResource(R.string.commands_cancel))
                }
            }
        )
    }
}
