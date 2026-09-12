package com.jcversa.swiftslate.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.jcversa.swiftslate.R
import com.jcversa.swiftslate.api.ApiClientUtils
import com.jcversa.swiftslate.api.OpenAICompatibleClient
import com.jcversa.swiftslate.manager.KeyManager
import com.jcversa.swiftslate.service.CommandOutcome
import com.jcversa.swiftslate.model.DeepSeekModels
import com.jcversa.swiftslate.model.NvidiaModels
import com.jcversa.swiftslate.model.OpenRouterModels
import com.jcversa.swiftslate.model.PrefKeys
import com.jcversa.swiftslate.model.ProviderType
import com.jcversa.swiftslate.provider.DeepSeekConfig
import com.jcversa.swiftslate.provider.NvidiaConfig
import com.jcversa.swiftslate.provider.OpenRouterConfig
import com.jcversa.swiftslate.provider.Providers
import com.jcversa.swiftslate.service.runTextCommand
import com.jcversa.swiftslate.api.GeminiClient
import com.jcversa.swiftslate.ui.components.LocalSlateRhythm
import com.jcversa.swiftslate.ui.components.SlateCard
import com.jcversa.swiftslate.ui.components.SlateTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private val onboardingProviders = listOf(
    ProviderType.NVIDIA,
    ProviderType.OPENROUTER,
    ProviderType.DEEPSEEK
)

private fun onboardingModels(provider: String): List<String> = when (provider) {
    ProviderType.NVIDIA -> listOf(
        NvidiaModels.DEFAULT,
        "meta/llama-3.3-70b-instruct",
        "mistralai/mistral-large-2-instruct"
    )
    ProviderType.OPENROUTER -> listOf(
        OpenRouterModels.DEFAULT,
        "deepseek/deepseek-chat-v3-0324:free",
        "qwen/qwen3-32b:free"
    )
    else -> listOf(
        DeepSeekModels.DEFAULT,
        "deepseek-chat",
        "deepseek-reasoner"
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    prefs: SharedPreferences,
    keyManager: KeyManager,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val rhythm = LocalSlateRhythm.current
    val scope = rememberCoroutineScope()
    val openAIClient = remember { OpenAICompatibleClient() }
    val geminiClient = remember { GeminiClient() }
    var step by remember { mutableStateOf(0) }
    var providerType by remember {
        mutableStateOf(
            prefs.getString(PrefKeys.PROVIDER_TYPE, ProviderType.NVIDIA)
                ?.takeIf { it in onboardingProviders } ?: ProviderType.NVIDIA
        )
    }
    var selectedModel by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var isTestingKey by remember { mutableStateOf(false) }
    var keyMessage by remember { mutableStateOf<String?>(null) }
    var keySuccess by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(false) }
    var serviceEnabled by remember { mutableStateOf(isSwiftSlateServiceEnabled(context)) }
    var testOutput by remember { mutableStateOf<String?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    var isTestingCommand by remember { mutableStateOf(false) }

    val config = Providers.forType(providerType)
    val onboardingKeySuccess = stringResource(R.string.onboarding_key_success)
    val onboardingKeyFailed = stringResource(R.string.onboarding_key_failed)
    val keystoreError = stringResource(R.string.keys_keystore_error)
    val authRequired = stringResource(R.string.error_provider_auth_required)

    val providerLabel = when (providerType) {
        ProviderType.OPENROUTER -> stringResource(R.string.settings_provider_openrouter)
        ProviderType.DEEPSEEK -> stringResource(R.string.settings_provider_deepseek)
        else -> stringResource(R.string.settings_provider_nvidia)
    }

    LaunchedEffect(providerType) {
        val availableModels = onboardingModels(providerType)
        val storedModel = prefs.getString(config.modelPrefKey, "").orEmpty()
        selectedModel = storedModel.takeIf { it in availableModels } ?: config.defaultModel
        prefs.edit()
            .putString(PrefKeys.PROVIDER_TYPE, providerType)
            .putString(config.modelPrefKey, selectedModel)
            .apply()
        keyMessage = null
        keySuccess = false
        apiKey = ""
        hasKey = false
    }

    LaunchedEffect(providerType) {
        hasKey = withContext(Dispatchers.IO) { keyManager.getKeys(providerType).isNotEmpty() }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = isSwiftSlateServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun finishSetup(withReminder: Boolean) {
        prefs.edit()
            .putBoolean(PrefKeys.ONBOARDING_COMPLETED, true)
            .putBoolean("onboarding_reminder", withReminder)
            .apply()
        onComplete()
    }

    fun validateAndStoreKey() {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank() || isTestingKey) return
        isTestingKey = true
        keyMessage = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (keyManager.getKeys(providerType).contains(trimmed)) {
                    Result.success(Unit)
                } else {
                    val endpoint = when (providerType) {
                        ProviderType.OPENROUTER -> OpenRouterConfig.ENDPOINT
                        ProviderType.DEEPSEEK -> DeepSeekConfig.ENDPOINT
                        else -> NvidiaConfig.ENDPOINT
                    }
                    openAIClient.validateKey(trimmed, endpoint).map { Unit }
                }
            }
            if (result.isSuccess) {
                val stored = withContext(Dispatchers.IO) { keyManager.addKey(trimmed, providerType) }
                keySuccess = stored
                keyMessage = if (stored) onboardingKeySuccess else keystoreError
                hasKey = stored || hasKey
                if (stored) apiKey = ""
            } else {
                keySuccess = false
                val raw = result.exceptionOrNull()?.message.orEmpty()
                keyMessage = if (raw.contains(ApiClientUtils.SIGNIN_REQUIRED_MARKER)) {
                    authRequired
                } else {
                    onboardingKeyFailed
                }
            }
            isTestingKey = false
        }
    }

    fun testCommand() {
        if (isTestingCommand) return
        testOutput = null
        testError = null
        isTestingCommand = true
        scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    withTimeout(90_000L) {
                        runTextCommand(
                            context,
                            keyManager,
                            geminiClient,
                            openAIClient,
                            "Rewrite this sentence to be clearer.",
                            "I need help to make this sentence clearer."
                        )
                    }
                }
            } catch (_: Exception) {
                CommandOutcome.Failure(context.getString(R.string.onboarding_test_failed))
            }
            isTestingCommand = false
            when (outcome) {
                is CommandOutcome.Success -> testOutput = outcome.text
                is CommandOutcome.Unavailable -> testError = outcome.message
                is CommandOutcome.Failure -> testError = context.getString(R.string.onboarding_test_failed)
                is CommandOutcome.Refusal -> testError = context.getString(R.string.onboarding_test_failed)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = rhythm.screenPaddingH, vertical = rhythm.screenPaddingV),
        verticalArrangement = Arrangement.spacedBy(rhythm.cardGap)
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = stringResource(R.string.onboarding_progress, step + 1, 5),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        when (step) {
            0 -> {
                SlateCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.onboarding_provider_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.onboarding_provider_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        onboardingProviders.forEach { candidate ->
                            val selected = candidate == providerType
                            val label = when (candidate) {
                                ProviderType.OPENROUTER -> stringResource(R.string.settings_provider_openrouter)
                                ProviderType.DEEPSEEK -> stringResource(R.string.settings_provider_deepseek)
                                else -> stringResource(R.string.settings_provider_nvidia)
                            }
                            Surface(
                                onClick = { providerType = candidate },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                                    Spacer(modifier = Modifier.weight(1f))
                                    if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.onboarding_next))
                }
            }
            1 -> {
                SlateCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Key, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.onboarding_key_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.onboarding_key_message, providerLabel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(14.dp))
                        SlateTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it.take(256); keyMessage = null },
                            label = { Text(stringResource(R.string.keys_api_key_label)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { validateAndStoreKey() },
                            enabled = apiKey.isNotBlank() && !isTestingKey && keyManager.keystoreAvailable,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            if (isTestingKey) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            } else Text(stringResource(R.string.onboarding_key_validate))
                        }
                        keyMessage?.let { message ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(message, color = if (keySuccess) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { step = 0 }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.onboarding_back)) }
                    Button(onClick = { step = 2 }, enabled = keySuccess || hasKey, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.onboarding_next)) }
                }
            }
            2 -> {
                SlateCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Settings, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.onboarding_model_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            stringResource(R.string.onboarding_model_message),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        onboardingModels(providerType).forEach { candidate ->
                            val selected = candidate == selectedModel
                            Surface(
                                onClick = {
                                    selectedModel = candidate
                                    prefs.edit().putString(config.modelPrefKey, candidate).apply()
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = if (selected) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        candidate,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { step = 1 }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.onboarding_back)) }
                    Button(onClick = { step = 3 }, enabled = selectedModel.isNotBlank(), modifier = Modifier.weight(1f)) { Text(stringResource(R.string.onboarding_next)) }
                }
            }
            3 -> {
                SlateCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Settings, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.onboarding_accessibility_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.onboarding_accessibility_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            Icon(Icons.Rounded.OpenInNew, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.onboarding_open_accessibility))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            if (serviceEnabled) stringResource(R.string.onboarding_accessibility_ready)
                            else stringResource(R.string.onboarding_accessibility_waiting),
                            color = if (serviceEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { step = 2 }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.onboarding_back)) }
                    Button(onClick = { step = 4 }, enabled = serviceEnabled, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.onboarding_next)) }
                }
            }
            else -> {
                SlateCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.onboarding_test_title), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.onboarding_test_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = { testCommand() },
                            enabled = !isTestingCommand,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) {
                            if (isTestingCommand) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                            else Text(stringResource(R.string.onboarding_test_button))
                        }
                        testOutput?.let {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(it, color = MaterialTheme.colorScheme.tertiary)
                        }
                        testError?.let {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { step = 3 }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.onboarding_back)) }
                    Button(onClick = { finishSetup(false) }, enabled = testOutput != null, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.onboarding_finish)) }
                }
            }
        }

        TextButton(
            onClick = { finishSetup(true) },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text(stringResource(R.string.onboarding_later))
        }
    }
}

private fun isSwiftSlateServiceEnabled(context: Context): Boolean {
    return try {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC).any {
            it.resolveInfo.serviceInfo.packageName == context.packageName
        }
    } catch (_: Exception) {
        false
    }
}
