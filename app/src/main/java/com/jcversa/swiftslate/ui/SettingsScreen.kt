package com.jcversa.swiftslate.ui

import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jcversa.swiftslate.BuildConfig
import com.jcversa.swiftslate.R
import com.jcversa.swiftslate.api.ApiClientUtils
import com.jcversa.swiftslate.api.GeminiClient
import com.jcversa.swiftslate.api.OpenAICompatibleClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.jcversa.swiftslate.manager.ApiKeyBackupManager
import com.jcversa.swiftslate.manager.CommandManager
import com.jcversa.swiftslate.manager.HistoryManager
import com.jcversa.swiftslate.manager.KeyManager
import com.jcversa.swiftslate.manager.ProviderModelsCache
import com.jcversa.swiftslate.model.GroqModels
import com.jcversa.swiftslate.model.OpenAIModels
import com.jcversa.swiftslate.model.PrefKeys
import com.jcversa.swiftslate.model.ProviderType
import com.jcversa.swiftslate.provider.EndpointValidator
import com.jcversa.swiftslate.provider.Providers
import com.jcversa.swiftslate.service.BackgroundReliability
import com.jcversa.swiftslate.ui.components.LocalSlateRhythm
import com.jcversa.swiftslate.ui.components.SlateCard
import com.jcversa.swiftslate.ui.components.SlateDivider
import com.jcversa.swiftslate.ui.components.SlateTextField
import com.jcversa.swiftslate.ui.components.AnimateEntrance

private const val SAFE_BACKUP_VERSION = 2

private enum class SecureBackupAction { EXPORT, IMPORT }

/** Exports configuration without API keys, history contents, or endpoint credentials. */
private fun buildSafeBackup(commandManager: CommandManager, prefs: SharedPreferences): String {
    val settings = JSONObject().apply {
        put(PrefKeys.PROVIDER_TYPE, prefs.getString(PrefKeys.PROVIDER_TYPE, null) ?: ProviderType.GEMINI)
        put(PrefKeys.GEMINI_MODEL, prefs.getString(PrefKeys.GEMINI_MODEL, "").orEmpty())
        put(PrefKeys.GROQ_MODEL, prefs.getString(PrefKeys.GROQ_MODEL, "").orEmpty())
        put(PrefKeys.NVIDIA_MODEL, prefs.getString(PrefKeys.NVIDIA_MODEL, "").orEmpty())
        put(PrefKeys.OPENROUTER_MODEL, prefs.getString(PrefKeys.OPENROUTER_MODEL, "").orEmpty())
        put(PrefKeys.DEEPSEEK_MODEL, prefs.getString(PrefKeys.DEEPSEEK_MODEL, "").orEmpty())
        put(PrefKeys.CUSTOM_MODEL, prefs.getString(PrefKeys.CUSTOM_MODEL, "").orEmpty())
        put(PrefKeys.TEMPERATURE, prefs.getFloat(PrefKeys.TEMPERATURE, 0.5f).toDouble())
        put(PrefKeys.PRIVACY_MODE, prefs.getBoolean(PrefKeys.PRIVACY_MODE, false))
        put(
            PrefKeys.TYPING_ANIMATION_ENABLED,
            prefs.getBoolean(PrefKeys.TYPING_ANIMATION_ENABLED, true)
        )
        put("trigger_prefix", prefs.getString(CommandManager.PREF_TRIGGER_PREFIX, CommandManager.DEFAULT_PREFIX))
    }
    return JSONObject().apply {
        put("format", "swiftslate-safe-backup")
        put("version", SAFE_BACKUP_VERSION)
        put("commands", JSONArray(commandManager.exportCommands()))
        put("settings", settings)
    }.toString(2)
}

/** Accepts the current safe envelope and older command-only JSON backups. */
private fun importSafeBackup(json: String, commandManager: CommandManager, prefs: SharedPreferences): Boolean {
    return try {
        val root = JSONObject(json)
        val settings = root.optJSONObject("settings")
        settings?.optString("trigger_prefix")?.takeIf { it.isNotBlank() }?.let { prefix ->
            commandManager.setTriggerPrefix(prefix)
        }
        val commands = root.optJSONArray("commands") ?: return false
        if (!commandManager.importCommands(commands.toString())) return false
        if (settings != null) {
            val editor = prefs.edit()
            settings.optString(PrefKeys.PROVIDER_TYPE).takeIf {
                it.isNotBlank() && ProviderType.isValid(it)
            }?.let {
                editor.putString(PrefKeys.PROVIDER_TYPE, it)
            }
            listOf(
                PrefKeys.GEMINI_MODEL,
                PrefKeys.GROQ_MODEL,
                PrefKeys.NVIDIA_MODEL,
                PrefKeys.OPENROUTER_MODEL,
                PrefKeys.DEEPSEEK_MODEL,
                PrefKeys.CUSTOM_MODEL
            ).forEach { key ->
                if (settings.has(key)) editor.putString(key, settings.optString(key))
            }
            if (settings.has(PrefKeys.TEMPERATURE)) {
                editor.putFloat(PrefKeys.TEMPERATURE, settings.optDouble(PrefKeys.TEMPERATURE, 0.5).toFloat())
            }
            if (settings.has(PrefKeys.PRIVACY_MODE)) {
                editor.putBoolean(PrefKeys.PRIVACY_MODE, settings.optBoolean(PrefKeys.PRIVACY_MODE, false))
            }
            if (settings.has(PrefKeys.TYPING_ANIMATION_ENABLED)) {
                editor.putBoolean(
                    PrefKeys.TYPING_ANIMATION_ENABLED,
                    settings.optBoolean(PrefKeys.TYPING_ANIMATION_ENABLED, true)
                )
            }
            editor.apply()
        }
        true
    } catch (_: Exception) {
        // Keep support for pre-envelope backups that contained only a JSON array of commands.
        commandManager.importCommands(json)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    commandManager: CommandManager,
    prefs: SharedPreferences,
    keyManager: KeyManager,
    openSecureBackup: Boolean = false,
    onSecureBackupRequestConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var accessibilityEnabled by remember {
        mutableStateOf(BackgroundReliability.isAccessibilityServiceEnabled(context))
    }
    var batteryOptimizationExempt by remember {
        mutableStateOf(BackgroundReliability.isBatteryOptimizationExempt(context))
    }
    var privacyMode by remember {
        mutableStateOf(prefs.getBoolean(PrefKeys.PRIVACY_MODE, false))
    }
    var typingAnimationEnabled by remember {
        mutableStateOf(prefs.getBoolean(PrefKeys.TYPING_ANIMATION_ENABLED, true))
    }
    val historyManager = remember { HistoryManager(context) }
    var historyEnabled by remember { mutableStateOf(historyManager.isEnabled) }
    var historyRetentionDays by remember { mutableIntStateOf(historyManager.retentionDays) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = BackgroundReliability.isAccessibilityServiceEnabled(context)
                batteryOptimizationExempt = BackgroundReliability.isBatteryOptimizationExempt(context)
                BackgroundReliability.refreshRecoveryNotification(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val scope = rememberCoroutineScope()
    var saveEndpointJob by remember { mutableStateOf<Job?>(null) }
    var saveModelJob by remember { mutableStateOf<Job?>(null) }

    val storedProviderType = prefs.getString(PrefKeys.PROVIDER_TYPE, null)
    var providerConfigurationInvalid by remember {
        mutableStateOf(storedProviderType != null && !ProviderType.isValid(storedProviderType))
    }
    var providerType by remember {
        mutableStateOf(ProviderType.storedOrNull(storedProviderType) ?: ProviderType.GEMINI)
    }
    var providerExpanded by remember { mutableStateOf(false) }

    var selectedModel by remember { mutableStateOf(prefs.getString(PrefKeys.GEMINI_MODEL, "") ?: "") }
    var modelExpanded by remember { mutableStateOf(false) }
    var geminiModelList by remember { mutableStateOf(ProviderModelsCache.get(ProviderType.GEMINI)?.models ?: emptyList()) }

    var groqModel by remember { mutableStateOf(prefs.getString(PrefKeys.GROQ_MODEL, "") ?: "") }
    var groqModelExpanded by remember { mutableStateOf(false) }
    var groqModelList by remember { mutableStateOf(ProviderModelsCache.get(ProviderType.GROQ)?.models ?: emptyList()) }
    // The three fixed OpenAI-compatible providers share one dropdown state; the
    // selected value is restored from that provider's own preference on switch.
    var managedModel by remember { mutableStateOf("") }
    var managedModelExpanded by remember { mutableStateOf(false) }
    var managedModelList by remember { mutableStateOf<List<String>>(emptyList()) }
    var isFetchingManagedModels by remember { mutableStateOf(false) }

    var customEndpoint by rememberSaveable { mutableStateOf(prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: "") }
    var customModel by rememberSaveable { mutableStateOf(prefs.getString(PrefKeys.CUSTOM_MODEL, "") ?: "") }
    var endpointError by remember { mutableStateOf<String?>(null) }
    // Fetched model ids for the Custom provider dropdown. Session state only — refetched on
    // demand, never persisted (the stored pref stays the plain custom_model string).
    var customModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var customModelExpanded by remember { mutableStateOf(false) }
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchMessage by remember { mutableStateOf<String?>(null) }
    var fetchSuccess by remember { mutableStateOf(false) }
    var isFetchingGeminiModels by remember { mutableStateOf(false) }
    var isFetchingGroqModels by remember { mutableStateOf(false) }
    var apiKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    var apiKeysProvider by remember { mutableStateOf<String?>(null) }
    var modelFetchJob by remember { mutableStateOf<Job?>(null) }
    val openAIClient = remember { OpenAICompatibleClient() }
    val geminiClient = remember { GeminiClient() }

    var triggerPrefix by remember { mutableStateOf(commandManager.getTriggerPrefix()) }
    var prefixError by remember { mutableStateOf<String?>(null) }
    var temperature by remember { mutableStateOf(prefs.getFloat(PrefKeys.TEMPERATURE, 0.5f)) }

    val prefixErrorLength = stringResource(R.string.settings_prefix_error_length)
    val prefixErrorWhitespace = stringResource(R.string.settings_prefix_error_whitespace)
    val prefixErrorAlphanumeric = stringResource(R.string.settings_prefix_error_alphanumeric)
    val endpointErrorScheme = stringResource(R.string.settings_endpoint_error_scheme)
    val endpointErrorSpaces = stringResource(R.string.settings_endpoint_error_spaces)
    val endpointCleartextWarning = stringResource(R.string.settings_endpoint_cleartext_warning)
    val fetchModelsMsg = stringResource(R.string.settings_fetch_models)
    val fetchingModelsMsg = stringResource(R.string.settings_fetch_models_loading)
    val modelsLoadedMsg = stringResource(R.string.settings_fetch_models_success)
    val modelsEmptyMsg = stringResource(R.string.settings_fetch_models_empty)
    val modelsFailedMsg = stringResource(R.string.settings_fetch_models_failed)
    val signinRequiredMsg = stringResource(R.string.error_provider_auth_required)

    // Registered keys are decrypted through the Keystore — load off the main thread, as
    // KeysScreen does. Clear the previous provider's keys before loading the new namespace;
    // otherwise a provider switch can briefly send a Gemini key to Groq (or vice versa).
    LaunchedEffect(providerType, providerConfigurationInvalid) {
        modelFetchJob?.cancel()
        modelFetchJob = null
        isFetchingGeminiModels = false
        isFetchingGroqModels = false
        isFetchingManagedModels = false
        isFetchingModels = false
        apiKeys = emptyList()
        apiKeysProvider = null
        if (!providerConfigurationInvalid) {
            val loaded = withContext(Dispatchers.IO) { keyManager.getKeys(providerType) }
            apiKeys = loaded
            apiKeysProvider = providerType
        }
    }

    LaunchedEffect(privacyMode) {
        if (privacyMode) {
            modelFetchJob?.cancel()
            modelFetchJob = null
            isFetchingGeminiModels = false
            isFetchingGroqModels = false
            isFetchingManagedModels = false
            isFetchingModels = false
        }
    }

    // Fetches one provider's live model list (issue #148). Groq rides the existing
    // OpenAI-compatible prober against its fixed endpoint; Gemini gets a native
    // listing call. Results land in [ProviderModelsCache] plus local state so the
    // dropdown renders without refetching on every visit.
    fun startModelFetch(type: String) {
        val isGemini = type == ProviderType.GEMINI
        val isGroq = type == ProviderType.GROQ
        val isManaged = type == ProviderType.NVIDIA ||
            type == ProviderType.OPENROUTER || type == ProviderType.DEEPSEEK
        if (!isGemini && !isGroq && !isManaged) return
        if (providerConfigurationInvalid || privacyMode || apiKeysProvider != type) return
        if (isGemini && isFetchingGeminiModels) return
        if (isGroq && isFetchingGroqModels) return
        if (isManaged && isFetchingManagedModels) return

        val key = apiKeys.firstOrNull() ?: return
        val config = Providers.forType(type)
        if (isGemini) isFetchingGeminiModels = true
        else if (isGroq) isFetchingGroqModels = true
        else isFetchingManagedModels = true

        modelFetchJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                when {
                    isGemini -> geminiClient.fetchModels(key)
                    else -> openAIClient.fetchModels(key, config.resolveEndpoint("")).map { ids ->
                        val chatIds = ids.filter { id ->
                            if (isGroq) GroqModels.isChatCandidate(id)
                            else OpenAIModels.isChatCandidate(id)
                        }
                        // openrouter/free is a router target, not a regular catalog
                        // entry, so keep it selectable even when /models omits it.
                        if (type == ProviderType.OPENROUTER && config.defaultModel !in chatIds) {
                            listOf(config.defaultModel) + chatIds
                        } else {
                            chatIds
                        }
                    }
                }
            }
            // The provider effect cancels old jobs and clears apiKeysProvider before loading
            // another namespace. Do not let a late response repopulate the new provider's UI.
            if (apiKeysProvider != type || privacyMode || providerConfigurationInvalid) return@launch
            val models = result.getOrNull().orEmpty()
            val success = result.isSuccess && models.isNotEmpty()
            val currentModels = when {
                isGemini -> geminiModelList
                isGroq -> groqModelList
                else -> managedModelList
            }
            val toCache = if (success) models else (ProviderModelsCache.get(type)?.models ?: currentModels)
            ProviderModelsCache.put(type, ProviderModelsCache.Entry(toCache, attempted = true))

            when {
                isGemini -> {
                    isFetchingGeminiModels = false
                    if (success) {
                        geminiModelList = models
                        if (selectedModel.isBlank()) {
                            val pick = preferredModel(models, config.defaultModel)
                            selectedModel = pick
                            prefs.edit().putString(config.modelPrefKey, pick).apply()
                        }
                    }
                }
                isGroq -> {
                    isFetchingGroqModels = false
                    if (success) {
                        groqModelList = models
                        if (groqModel.isBlank()) {
                            val pick = preferredModel(models, config.defaultModel)
                            groqModel = pick
                            prefs.edit().putString(config.modelPrefKey, pick).apply()
                        }
                    }
                }
                else -> {
                    isFetchingManagedModels = false
                    if (success) {
                        managedModelList = models
                        if (managedModel.isBlank()) {
                            val pick = preferredModel(models, config.defaultModel)
                            managedModel = pick
                            prefs.edit().putString(config.modelPrefKey, pick).apply()
                        }
                    }
                }
            }
        }
    }

    // Model catalogs are never fetched merely by entering Settings. Opening a dropdown is
    // the explicit user action that may contact the selected provider.

    // Restore the independent model choice when moving between NVIDIA, OpenRouter
    // and DeepSeek. Their lists remain session-only and are kept in the cache above.
    LaunchedEffect(providerType) {
        val managed = providerType == ProviderType.NVIDIA ||
            providerType == ProviderType.OPENROUTER || providerType == ProviderType.DEEPSEEK
        if (managed) {
            val config = Providers.forType(providerType)
            managedModel = prefs.getString(config.modelPrefKey, "").orEmpty()
            managedModelList = ProviderModelsCache.get(providerType)?.models ?: emptyList()
            managedModelExpanded = false
        }
    }


    var backupMessage by remember { mutableStateOf<String?>(null) }
    var backupSuccess by remember { mutableStateOf(false) }
    var showImportConfirm by remember { mutableStateOf(false) }

    val apiKeyBackupManager = remember { ApiKeyBackupManager(context, keyManager) }
    var secureBackupAction by remember { mutableStateOf<SecureBackupAction?>(null) }
    var securePassphrase by remember { mutableStateOf("") }
    var secureBackupMessage by remember { mutableStateOf<String?>(null) }
    var secureBackupSuccess by remember { mutableStateOf(false) }
    var secureBackupChooserVisible by remember { mutableStateOf(false) }
    var pendingExportPassphrase by remember { mutableStateOf<String?>(null) }
    var pendingImportFile by remember { mutableStateOf<String?>(null) }
    var pendingImportData by remember {
        mutableStateOf<Pair<ApiKeyBackupManager.ImportedData, ApiKeyBackupManager.ImportSummary>?>(null)
    }

    LaunchedEffect(openSecureBackup) {
        if (openSecureBackup) {
            secureBackupChooserVisible = true
            onSecureBackupRequestConsumed()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            saveEndpointJob?.cancel()
            saveModelJob?.cancel()
            val editor = prefs.edit()
            var needsWrite = false
            if (customEndpoint != (prefs.getString(PrefKeys.CUSTOM_ENDPOINT, "") ?: "")) {
                val isValid = customEndpoint.isBlank() ||
                    EndpointValidator.validate(customEndpoint) == EndpointValidator.Error.NONE
                if (isValid) {
                    editor.putString(PrefKeys.CUSTOM_ENDPOINT, customEndpoint)
                    needsWrite = true
                }
            }
            if (customModel != (prefs.getString(PrefKeys.CUSTOM_MODEL, "") ?: "")) {
                editor.putString(PrefKeys.CUSTOM_MODEL, customModel)
                needsWrite = true
            }
            if (needsWrite) editor.apply()
        }
    }
    val exportSuccessMsg = stringResource(R.string.backup_export_success)
    val exportErrorMsg = stringResource(R.string.backup_export_error)
    val importSuccessMsg = stringResource(R.string.backup_import_success)
    val importErrorMsg = stringResource(R.string.backup_import_error)
    val secureExportSuccessMsg = stringResource(R.string.secure_backup_export_success)
    val secureImportSuccessMsg = stringResource(R.string.secure_backup_import_success)
    val secureBackupErrorMsg = stringResource(R.string.secure_backup_error)
    val securePassphraseShortMsg = stringResource(R.string.secure_backup_passphrase_short)
    val secureImportConfirmMsg = stringResource(R.string.secure_backup_import_confirm)
    val secureChooserMessage = stringResource(R.string.secure_backup_chooser_message)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(it)?.use { os ->
                            os.write(buildSafeBackup(commandManager, prefs).toByteArray())
                        }
                    }
                    backupMessage = exportSuccessMsg
                    backupSuccess = true
                } catch (_: Exception) {
                    backupMessage = exportErrorMsg
                    backupSuccess = false
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            scope.launch {
                try {
                    val json = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                            val text = reader.readText().removePrefix("\uFEFF")
                            if (text.length > 1_000_000) null else text
                        } ?: ""
                    }
                    val imported = withContext(Dispatchers.IO) {
                        importSafeBackup(json, commandManager, prefs)
                    }
                    if (imported) {
                        backupMessage = importSuccessMsg
                        backupSuccess = true
                    } else {
                        backupMessage = importErrorMsg
                        backupSuccess = false
                    }
                } catch (_: Exception) {
                    backupMessage = importErrorMsg
                    backupSuccess = false
                }
            }
        }
    }

    val secureExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val passphrase = pendingExportPassphrase
        pendingExportPassphrase = null
        if (uri != null && passphrase != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val encrypted = apiKeyBackupManager.export(passphrase)
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            output.write(encrypted.toByteArray(Charsets.UTF_8))
                        } ?: error("Unable to open export destination")
                    }
                    secureBackupMessage = secureExportSuccessMsg
                    secureBackupSuccess = true
                } catch (_: Exception) {
                    secureBackupMessage = secureBackupErrorMsg
                    secureBackupSuccess = false
                } finally {
                    passphrase.toCharArray().fill('\u0000')
                }
            }
        }
    }

    val secureImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val encrypted = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                            val text = reader.readText()
                            if (text.toByteArray(Charsets.UTF_8).size > 1_000_000) null else text
                        } ?: ""
                    }
                    if (encrypted.isBlank()) error("Empty backup")
                    pendingImportFile = encrypted
                    securePassphrase = ""
                    secureBackupAction = SecureBackupAction.IMPORT
                } catch (_: Exception) {
                    secureBackupMessage = secureBackupErrorMsg
                    secureBackupSuccess = false
                }
            }
        }
    }

    val rhythm = LocalSlateRhythm.current
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
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
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.settings_subtitle),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Card 1: Provider + Model Configuration
        AnimateEntrance(index = 1) {
            SlateCard {
                Column(modifier = Modifier.padding(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_engine_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = stringResource(R.string.settings_provider_title),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                ExposedDropdownMenuBox(
                    expanded = providerExpanded,
                    onExpandedChange = { providerExpanded = !providerExpanded }
                ) {
                    SlateTextField(
                        value = if (providerConfigurationInvalid) {
                            stringResource(R.string.error_provider_selection_invalid)
                        } else {
                            when (providerType) {
                                ProviderType.GEMINI -> stringResource(R.string.settings_provider_gemini)
                                ProviderType.GROQ -> stringResource(R.string.settings_provider_groq)
                                ProviderType.NVIDIA -> stringResource(R.string.settings_provider_nvidia)
                                ProviderType.OPENROUTER -> stringResource(R.string.settings_provider_openrouter)
                                ProviderType.DEEPSEEK -> stringResource(R.string.settings_provider_deepseek)
                                else -> stringResource(R.string.settings_provider_custom)
                            }
                        },
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        containerColor = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_provider_gemini)) },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                providerType = ProviderType.GEMINI
                                providerConfigurationInvalid = false
                                prefs.edit().putString(PrefKeys.PROVIDER_TYPE, ProviderType.GEMINI).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                                providerExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_provider_groq)) },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                providerType = ProviderType.GROQ
                                providerConfigurationInvalid = false
                                prefs.edit().putString(PrefKeys.PROVIDER_TYPE, ProviderType.GROQ).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                                providerExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_provider_nvidia)) },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                providerType = ProviderType.NVIDIA
                                providerConfigurationInvalid = false
                                prefs.edit().putString(PrefKeys.PROVIDER_TYPE, ProviderType.NVIDIA).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                                providerExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_provider_openrouter)) },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                providerType = ProviderType.OPENROUTER
                                providerConfigurationInvalid = false
                                prefs.edit().putString(PrefKeys.PROVIDER_TYPE, ProviderType.OPENROUTER).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                                providerExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_provider_deepseek)) },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                providerType = ProviderType.DEEPSEEK
                                providerConfigurationInvalid = false
                                prefs.edit().putString(PrefKeys.PROVIDER_TYPE, ProviderType.DEEPSEEK).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                                providerExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.settings_provider_custom)) },
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                providerType = ProviderType.CUSTOM
                                providerConfigurationInvalid = false
                                prefs.edit().putString(PrefKeys.PROVIDER_TYPE, ProviderType.CUSTOM).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                                providerExpanded = false
                            }
                        )
                    }
                }

                if (providerConfigurationInvalid) {
                    Text(
                        text = stringResource(R.string.error_provider_selection_invalid),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (providerType == ProviderType.GEMINI) {
                    Text(
                        text = stringResource(R.string.settings_model_title),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    DynamicModelDropdown(
                        selectedModel = if (apiKeys.isEmpty() || selectedModel.isBlank()) "" else selectedModel,
                        enabled = apiKeys.isNotEmpty() && !providerConfigurationInvalid,
                        expanded = modelExpanded,
                        onExpandedChange = { isOpening ->
                            modelExpanded = isOpening
                        },
                        models = geminiModelList,
                        onSelect = { id ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            selectedModel = id
                            prefs.edit().putString(PrefKeys.GEMINI_MODEL, id).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                            modelExpanded = false
                        },
                        onDismiss = { modelExpanded = false },
                        isFetching = isFetchingGeminiModels,
                        fetchingText = fetchingModelsMsg
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                startModelFetch(ProviderType.GEMINI)
                            },
                            enabled = apiKeysProvider == ProviderType.GEMINI &&
                                apiKeys.isNotEmpty() && !isFetchingGeminiModels &&
                                !privacyMode && !providerConfigurationInvalid
                        ) {
                            Text(if (isFetchingGeminiModels) fetchingModelsMsg else fetchModelsMsg)
                        }
                    }
                } else if (providerType == ProviderType.GROQ) {
                    Text(
                        text = stringResource(R.string.settings_model_title),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    DynamicModelDropdown(
                        selectedModel = if (apiKeys.isEmpty() || groqModel.isBlank()) "" else groqModel,
                        enabled = apiKeys.isNotEmpty() && !providerConfigurationInvalid,
                        expanded = groqModelExpanded,
                        onExpandedChange = { isOpening ->
                            groqModelExpanded = isOpening
                        },
                        models = groqModelList,
                        onSelect = { id ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            groqModel = id
                            prefs.edit().putString(PrefKeys.GROQ_MODEL, id).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                            groqModelExpanded = false
                        },
                        onDismiss = { groqModelExpanded = false },
                        isFetching = isFetchingGroqModels,
                        fetchingText = fetchingModelsMsg
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                startModelFetch(ProviderType.GROQ)
                            },
                            enabled = apiKeysProvider == ProviderType.GROQ &&
                                apiKeys.isNotEmpty() && !isFetchingGroqModels &&
                                !privacyMode && !providerConfigurationInvalid
                        ) {
                            Text(if (isFetchingGroqModels) fetchingModelsMsg else fetchModelsMsg)
                        }
                    }
                } else if (providerType == ProviderType.CUSTOM) {
                    Text(
                        text = stringResource(R.string.settings_endpoint_title),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    SlateTextField(
                        value = customEndpoint,
                        onValueChange = {
                            customEndpoint = it
                            customModels = emptyList()
                            fetchMessage = null
                            endpointError = when {
                                it.isBlank() -> null
                                it.contains(" ") -> endpointErrorSpaces
                                EndpointValidator.validate(it) == EndpointValidator.Error.NONE -> null
                                else -> endpointErrorScheme
                            }
                            if (endpointError == null) {
                                saveEndpointJob?.cancel()
                                saveEndpointJob = scope.launch {
                                    delay(500)
                                    prefs.edit().putString(PrefKeys.CUSTOM_ENDPOINT, it).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                                }
                            }
                        },
                        placeholder = { Text(stringResource(R.string.settings_endpoint_placeholder)) },
                        isError = endpointError != null
                    )
                    endpointError?.let { msg ->
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (endpointError == null && customEndpoint.trim().startsWith("http://", ignoreCase = true)) {
                        Text(
                            text = endpointCleartextWarning,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = stringResource(R.string.settings_model_title),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    if (customModels.isNotEmpty()) {
                        ExposedDropdownMenuBox(
                            expanded = customModelExpanded,
                            onExpandedChange = { customModelExpanded = !customModelExpanded }
                        ) {
                            SlateTextField(
                                value = customModel,
                                onValueChange = {
                                    customModel = it
                                    saveModelJob?.cancel()
                                    saveModelJob = scope.launch {
                                        delay(500)
                                        prefs.edit().putString(PrefKeys.CUSTOM_MODEL, it).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                                    }
                                },
                                placeholder = { Text(stringResource(R.string.settings_model_placeholder)) },
                                modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                            )
                            ExposedDropdownMenu(
                                containerColor = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(10.dp),
                                expanded = customModelExpanded,
                                onDismissRequest = { customModelExpanded = false }
                            ) {
                                customModels.forEach { id ->
                                    DropdownMenuItem(
                                        text = { Text(id) },
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            customModel = id
                                            saveModelJob?.cancel()
                                            prefs.edit().putString(PrefKeys.CUSTOM_MODEL, id).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                                            customModelExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        SlateTextField(
                            value = customModel,
                            onValueChange = {
                                customModel = it
                                saveModelJob?.cancel()
                                saveModelJob = scope.launch {
                                    delay(500)
                                    prefs.edit().putString(PrefKeys.CUSTOM_MODEL, it).remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                                }
                            },
                            placeholder = { Text(stringResource(R.string.settings_model_placeholder)) }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                isFetchingModels = true
                                fetchMessage = null
                                modelFetchJob = scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        val key = if (apiKeysProvider == ProviderType.CUSTOM) {
                                            apiKeys.firstOrNull()
                                        } else {
                                            null
                                        }
                                        openAIClient.fetchModels(key, customEndpoint)
                                    }
                                    if (apiKeysProvider != ProviderType.CUSTOM ||
                                        privacyMode || providerConfigurationInvalid
                                    ) return@launch
                                    isFetchingModels = false
                                    result.onSuccess { ids ->
                                        if (ids.isEmpty()) {
                                            customModels = emptyList()
                                            fetchMessage = modelsEmptyMsg
                                            fetchSuccess = false
                                        } else {
                                            customModels = ids
                                            customModelExpanded = false
                                            fetchMessage = String.format(modelsLoadedMsg, ids.size)
                                            fetchSuccess = true
                                        }
                                    }.onFailure { e ->
                                        customModels = emptyList()
                                        val raw = e.message ?: ""
                                        fetchMessage = if (raw.contains(ApiClientUtils.SIGNIN_REQUIRED_MARKER)) {
                                            signinRequiredMsg
                                        } else {
                                            modelsFailedMsg
                                        }
                                        fetchSuccess = false
                                    }
                                }
                            },
                            enabled = customEndpoint.isNotBlank() && endpointError == null &&
                                !isFetchingModels && !privacyMode && !providerConfigurationInvalid
                        ) {
                            Text(if (isFetchingModels) fetchingModelsMsg else fetchModelsMsg)
                        }
                    }
                    fetchMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = if (fetchSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                } else {
                    val managedConfig = Providers.forType(providerType)
                    Text(
                        text = stringResource(R.string.settings_model_title),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    DynamicModelDropdown(
                        selectedModel = if (apiKeys.isEmpty() || managedModel.isBlank()) "" else managedModel,
                        enabled = apiKeys.isNotEmpty() && !providerConfigurationInvalid,
                        expanded = managedModelExpanded,
                        onExpandedChange = { isOpening ->
                            managedModelExpanded = isOpening
                        },
                        models = managedModelList,
                        onSelect = { id ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            managedModel = id
                            prefs.edit().putString(managedConfig.modelPrefKey, id)
                                .remove(PrefKeys.STRUCTURED_OUTPUT_DISABLED_AT).apply()
                            managedModelExpanded = false
                        },
                        onDismiss = { managedModelExpanded = false },
                        isFetching = isFetchingManagedModels,
                        fetchingText = fetchingModelsMsg
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                startModelFetch(providerType)
                            },
                            enabled = apiKeysProvider == providerType &&
                                apiKeys.isNotEmpty() && !isFetchingManagedModels &&
                                !privacyMode && !providerConfigurationInvalid
                        ) {
                            Text(if (isFetchingManagedModels) fetchingModelsMsg else fetchModelsMsg)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = stringResource(R.string.settings_temperature_title),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = stringResource(R.string.settings_temperature_hint),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                    ) {
                        Text(
                            text = String.format("%.1f", temperature),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = temperature,
                    onValueChange = {
                        val newVal = Math.round(it * 10) / 10f
                        if (newVal != temperature) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            temperature = newVal
                        }
                    },
                    onValueChangeFinished = {
                        prefs.edit().putFloat(PrefKeys.TEMPERATURE, temperature).apply()
                    },
                    valueRange = 0f..2f,
                    steps = 19,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                )
            }
        }
    }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Card 2: Trigger Prefix
        AnimateEntrance(index = 2) {
            SlateCard {
                Column(modifier = Modifier.padding(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Keyboard,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_prefix_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_trigger_prefix_desc, triggerPrefix),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(end = 16.dp)
                    )
                    SlateTextField(
                        value = triggerPrefix,
                        onValueChange = { input ->
                            val filtered = input.take(1)
                            triggerPrefix = filtered
                            prefixError = when {
                                filtered.length != 1 -> prefixErrorLength
                                filtered[0].isWhitespace() -> prefixErrorWhitespace
                                filtered[0].isLetterOrDigit() -> prefixErrorAlphanumeric
                                else -> {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    scope.launch(Dispatchers.IO) {
                                        commandManager.setTriggerPrefix(filtered)
                                    }
                                    null
                                }
                            }
                        },
                        isError = prefixError != null,
                        modifier = Modifier.width(64.dp)
                    )
                }
                prefixError?.let { msg ->
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Card 3: Background reliability
        AnimateEntrance(index = 3) {
            SlateCard {
                Column(modifier = Modifier.padding(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.settings_reliability_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_reliability_desc),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (accessibilityEnabled && batteryOptimizationExempt) {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.28f)
                        } else {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
                        },
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (accessibilityEnabled && batteryOptimizationExempt) {
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = when {
                                    !accessibilityEnabled -> Icons.Rounded.PowerSettingsNew
                                    batteryOptimizationExempt -> Icons.Rounded.CheckCircle
                                    else -> Icons.Rounded.BatteryAlert
                                },
                                contentDescription = null,
                                tint = if (accessibilityEnabled && batteryOptimizationExempt) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = when {
                                    !accessibilityEnabled -> stringResource(R.string.settings_reliability_accessibility_needed)
                                    batteryOptimizationExempt -> stringResource(R.string.settings_reliability_ready)
                                    else -> stringResource(R.string.settings_reliability_battery_needed)
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (!accessibilityEnabled) {
                                BackgroundReliability.openAccessibilitySettings(context)
                            } else {
                                BackgroundReliability.openBatterySettings(context)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BatteryChargingFull,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            stringResource(
                                if (!accessibilityEnabled) {
                                    R.string.settings_reliability_open_accessibility
                                } else {
                                    R.string.settings_reliability_open_battery
                                }
                            )
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Card 4: Privacy mode
        AnimateEntrance(index = 4) {
            SlateCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = if (privacyMode) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_privacy_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = stringResource(R.string.settings_privacy_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = privacyMode,
                        onCheckedChange = { enabled ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            privacyMode = enabled
                            prefs.edit().putBoolean(PrefKeys.PRIVACY_MODE, enabled).apply()
                        }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        if (privacyMode) {
                            R.string.settings_privacy_enabled
                        } else {
                            R.string.settings_privacy_disabled
                        }
                    ),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (privacyMode) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Typing animation for AI replacements.
        AnimateEntrance(index = 5) {
            SlateCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.TextFields,
                        contentDescription = null,
                        tint = if (typingAnimationEnabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_typing_animation_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = stringResource(R.string.settings_typing_animation_desc),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = typingAnimationEnabled,
                        onCheckedChange = { enabled ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            typingAnimationEnabled = enabled
                            prefs.edit()
                                .putBoolean(PrefKeys.TYPING_ANIMATION_ENABLED, enabled)
                                .apply()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Optional local history: disabled by default and always removable in one action.
        AnimateEntrance(index = 6) {
            SlateCard {
                Column(modifier = Modifier.padding(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = if (historyEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_history_title),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_history_desc),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = historyEnabled,
                            onCheckedChange = { enabled ->
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                historyEnabled = enabled
                                scope.launch(Dispatchers.IO) {
                                    historyManager.setEnabled(enabled)
                                }
                            }
                        )
                    }
                    AnimatedVisibility(visible = historyEnabled) {
                        Column {
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = stringResource(R.string.settings_history_retention),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            val retentionOptions = listOf(7, 30, 90)
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                retentionOptions.forEachIndexed { index, days ->
                                    SegmentedButton(
                                        selected = historyRetentionDays == days,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            historyRetentionDays = days
                                            scope.launch(Dispatchers.IO) {
                                                historyManager.setRetentionDays(days)
                                            }
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = index,
                                            count = retentionOptions.size
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(stringResource(R.string.settings_history_days, days), fontSize = 11.sp)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { showClearHistoryConfirm = true },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_history_clear),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Card 7: Backup Vault
        AnimateEntrance(index = 7) {
            SlateCard {
                Column(modifier = Modifier.padding(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderZip,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_backup_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = stringResource(R.string.backup_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            backupMessage = null
                            exportLauncher.launch("swiftslate-commands.json")
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CloudDownload, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_export), fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            backupMessage = null
                            showImportConfirm = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CloudUpload, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.backup_import), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                AnimatedVisibility(
                    visible = backupMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    backupMessage?.let { msg ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (backupSuccess) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.25f)
                            else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (backupSuccess) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
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
                                    imageVector = if (backupSuccess) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
                                    contentDescription = null,
                                    tint = if (backupSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = msg,
                                    color = if (backupSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.secure_backup_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.secure_backup_desc),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            secureBackupMessage = null
                            securePassphrase = ""
                            secureBackupAction = SecureBackupAction.EXPORT
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.secure_backup_export), fontWeight = FontWeight.Bold)
                        }
                    }
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            secureBackupMessage = null
                            secureBackupChooserVisible = false
                            secureImportLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.LockOpen, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.secure_backup_import), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                AnimatedVisibility(
                    visible = secureBackupMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    secureBackupMessage?.let { message ->
                        Text(
                            text = message,
                            modifier = Modifier.padding(top = 10.dp),
                            color = if (secureBackupSuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Card 4: About, Credits, and Sponsor Section
        AnimateEntrance(index = 4) {
            SlateCard {
                Column(modifier = Modifier.padding(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_about_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    onClick = { uriHandler.openUri("https://github.com/JCVERSA/swiftslate/releases/latest") },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.settings_build_version, BuildConfig.VERSION_NAME),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.settings_check_updates),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    onClick = { uriHandler.openUri("https://github.com/sponsors/JCVERSA") },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.04f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = stringResource(R.string.settings_made_by),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.settings_sponsor_hint),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.settings_sponsor),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Rounded.Favorite,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
    }

    if (showClearHistoryConfirm) {
        AlertDialog(
            onDismissRequest = { showClearHistoryConfirm = false },
            title = { Text(stringResource(R.string.settings_history_clear_title)) },
            text = { Text(stringResource(R.string.settings_history_clear_message)) },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    scope.launch(Dispatchers.IO) {
                        historyManager.clear()
                    }
                    showClearHistoryConfirm = false
                }) {
                    Text(stringResource(R.string.settings_history_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryConfirm = false }) {
                    Text(stringResource(R.string.commands_cancel))
                }
            }
        )
    }

    if (showImportConfirm) {
        AlertDialog(
            onDismissRequest = { showImportConfirm = false },
            title = { Text(stringResource(R.string.backup_import)) },
            text = { Text(stringResource(R.string.backup_import_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showImportConfirm = false
                    importLauncher.launch(arrayOf("application/json"))
                }) { Text(stringResource(R.string.backup_import)) }
            },
            dismissButton = {
                TextButton(onClick = { showImportConfirm = false }) {
                    Text(stringResource(R.string.backup_import_cancel))
                }
            }
        )
    }

    if (secureBackupChooserVisible) {
        AlertDialog(
            onDismissRequest = { secureBackupChooserVisible = false },
            title = { Text(stringResource(R.string.secure_backup_title)) },
            text = { Text(secureChooserMessage) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        secureBackupChooserVisible = false
                        secureBackupMessage = null
                        securePassphrase = ""
                        secureBackupAction = SecureBackupAction.EXPORT
                    }) { Text(stringResource(R.string.secure_backup_export)) }
                    TextButton(onClick = {
                        secureBackupChooserVisible = false
                        secureBackupMessage = null
                        secureImportLauncher.launch(arrayOf("application/json", "application/octet-stream"))
                    }) { Text(stringResource(R.string.secure_backup_import)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { secureBackupChooserVisible = false }) {
                    Text(stringResource(R.string.backup_import_cancel))
                }
            }
        )
    }

    if (secureBackupAction != null) {
        AlertDialog(
            onDismissRequest = {
                secureBackupAction = null
                pendingImportFile = null
                securePassphrase = ""
            },
            title = {
                Text(
                    stringResource(
                        if (secureBackupAction == SecureBackupAction.EXPORT) {
                            R.string.secure_backup_export_title
                        } else {
                            R.string.secure_backup_import_title
                        }
                    )
                )
            },
            text = {
                Column {
                    Text(stringResource(R.string.secure_backup_passphrase_message))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = securePassphrase,
                        onValueChange = { securePassphrase = it },
                        label = { Text(stringResource(R.string.secure_backup_passphrase_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.secure_backup_passphrase_hint)) }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (!ApiKeyBackupManager.isPassphraseAcceptable(securePassphrase)) {
                        secureBackupMessage = securePassphraseShortMsg
                    } else if (secureBackupAction == SecureBackupAction.EXPORT) {
                        pendingExportPassphrase = securePassphrase
                        securePassphrase = ""
                        secureBackupAction = null
                        secureExportLauncher.launch("swiftslate-api-keys.json")
                    } else {
                        val encrypted = pendingImportFile
                        if (encrypted == null) {
                            secureBackupAction = null
                            secureBackupMessage = secureBackupErrorMsg
                            secureBackupSuccess = false
                        } else {
                            val passphrase = securePassphrase
                            secureBackupAction = null
                            scope.launch {
                                try {
                                    val inspected = withContext(Dispatchers.IO) {
                                        apiKeyBackupManager.inspect(encrypted, passphrase)
                                    }
                                    pendingImportData = inspected
                                } catch (_: Exception) {
                                    secureBackupMessage = secureBackupErrorMsg
                                    secureBackupSuccess = false
                                } finally {
                                    securePassphrase = ""
                                    pendingImportFile = null
                                    passphrase.toCharArray().fill('\u0000')
                                }
                            }
                        }
                    }
                }) {
                    Text(stringResource(R.string.secure_backup_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    secureBackupAction = null
                    pendingImportFile = null
                    securePassphrase = ""
                }) { Text(stringResource(R.string.backup_import_cancel)) }
            }
        )
    }

    pendingImportData?.let { (data, summary) ->
        AlertDialog(
            onDismissRequest = { pendingImportData = null },
            title = { Text(stringResource(R.string.secure_backup_import_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.secure_backup_import_confirm_details,
                        summary.keyCount,
                        summary.configuredProviderCount,
                        summary.modelsIncluded
                    ) + "\\n\\n" + secureImportConfirmMsg
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportData = null
                    scope.launch {
                        val applied = withContext(Dispatchers.IO) {
                            apiKeyBackupManager.apply(data)
                        }
                        if (applied) {
                            providerType = data.activeProvider
                            providerConfigurationInvalid = false
                            selectedModel = data.modelsByProvider[ProviderType.GEMINI].orEmpty()
                            groqModel = data.modelsByProvider[ProviderType.GROQ].orEmpty()
                            managedModel = data.modelsByProvider[data.activeProvider].orEmpty()
                            customModel = data.modelsByProvider[ProviderType.CUSTOM].orEmpty()
                            customEndpoint = data.customEndpoint
                            apiKeys = data.keysByProvider[data.activeProvider].orEmpty()
                            apiKeysProvider = data.activeProvider
                            secureBackupMessage = secureImportSuccessMsg
                            secureBackupSuccess = true
                        } else {
                            secureBackupMessage = secureBackupErrorMsg
                            secureBackupSuccess = false
                        }
                    }
                }) { Text(stringResource(R.string.secure_backup_import)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportData = null }) {
                    Text(stringResource(R.string.backup_import_cancel))
                }
            }
        )
    }
}

internal fun preferredModel(models: List<String>, default: String): String =
    if (models.contains(default)) default else models.first()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DynamicModelDropdown(
    selectedModel: String,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    models: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    isFetching: Boolean,
    fetchingText: String
) {
    val rhythm = LocalSlateRhythm.current
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) onExpandedChange(it) }
    ) {
        SlateTextField(
            value = selectedModel,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        )
        if (enabled && expanded) {
            ExposedDropdownMenu(
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(10.dp),
                expanded = expanded,
                onDismissRequest = onDismiss,
                modifier = Modifier.heightIn(max = 300.dp)
            ) {
                if (isFetching) {
                    DropdownMenuItem(
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = fetchingText,
                                    fontSize = rhythm.bodySize,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {},
                        enabled = false
                    )
                    if (models.isNotEmpty()) {
                        SlateDivider()
                    }
                }
                models.forEach { id ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = id,
                                color = if (id == selectedModel) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        },
                        onClick = { onSelect(id) }
                    )
                }
            }
        }
    }
}
