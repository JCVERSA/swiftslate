package com.jcversa.swiftslate.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.jcversa.swiftslate.R
import com.jcversa.swiftslate.api.ApiClientUtils
import com.jcversa.swiftslate.api.GeminiClient
import com.jcversa.swiftslate.api.OpenAICompatibleClient
import com.jcversa.swiftslate.manager.KeyManager
import com.jcversa.swiftslate.model.DeepSeekModels
import com.jcversa.swiftslate.model.NvidiaModels
import com.jcversa.swiftslate.model.OpenRouterModels
import com.jcversa.swiftslate.model.PrefKeys
import com.jcversa.swiftslate.model.ProviderType
import com.jcversa.swiftslate.provider.DeepSeekConfig
import com.jcversa.swiftslate.provider.NvidiaConfig
import com.jcversa.swiftslate.provider.OpenRouterConfig
import com.jcversa.swiftslate.provider.Providers
import com.jcversa.swiftslate.service.CommandOutcome
import com.jcversa.swiftslate.service.runTextCommand
import com.jcversa.swiftslate.ui.components.LocalSlateMotion
import com.jcversa.swiftslate.ui.components.LocalSlateRhythm
import com.jcversa.swiftslate.ui.components.SlateCard
import com.jcversa.swiftslate.ui.components.SlateMark
import com.jcversa.swiftslate.ui.components.SlateMorphIcon
import com.jcversa.swiftslate.ui.components.SlateMorphIconType
import com.jcversa.swiftslate.ui.components.SlateRhythm
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

@Composable
fun OnboardingScreen(
    prefs: SharedPreferences,
    keyManager: KeyManager,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val motion = LocalSlateMotion.current
    val scope = rememberCoroutineScope()
    val openAIClient = remember { OpenAICompatibleClient() }
    val geminiClient = remember { GeminiClient() }
    val scrollState = rememberScrollState()
    var step by remember { mutableStateOf(0) }
    val storedProviderType = prefs.getString(PrefKeys.PROVIDER_TYPE, null)
    var providerConfigurationInvalid by remember {
        mutableStateOf(storedProviderType != null && !ProviderType.isValid(storedProviderType))
    }
    var providerType by remember {
        mutableStateOf(
            ProviderType.storedOrNull(storedProviderType)
                ?.takeIf { it in onboardingProviders } ?: ProviderType.NVIDIA
        )
    }
    var selectedModel by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var isEditingKey by remember { mutableStateOf(false) }
    var isTestingKey by remember { mutableStateOf(false) }
    var keyMessage by remember { mutableStateOf<String?>(null) }
    var keySuccess by remember { mutableStateOf(false) }
    var hasKey by remember { mutableStateOf(false) }
    var serviceEnabled by remember { mutableStateOf(isSwiftSlateServiceEnabled(context)) }
    var testOutput by remember { mutableStateOf<String?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    var isTestingCommand by remember { mutableStateOf(false) }

    val config = Providers.forType(providerType)
    val keystoreAvailable = keyManager.keystoreAvailable
    val onboardingKeyFailed = stringResource(R.string.onboarding_key_failed)
    val keystoreError = stringResource(R.string.keys_keystore_error)
    val authRequired = stringResource(R.string.error_provider_auth_required)
    val onboardingTestFailed = stringResource(R.string.onboarding_test_failed)
    val providerLabel = if (providerConfigurationInvalid) {
        stringResource(R.string.error_provider_selection_invalid)
    } else {
        when (providerType) {
            ProviderType.OPENROUTER -> stringResource(R.string.settings_provider_openrouter)
            ProviderType.DEEPSEEK -> stringResource(R.string.settings_provider_deepseek)
            else -> stringResource(R.string.settings_provider_nvidia)
        }
    }

    LaunchedEffect(step) {
        scrollState.scrollTo(0)
    }

    LaunchedEffect(providerType, providerConfigurationInvalid) {
        if (providerConfigurationInvalid) {
            selectedModel = ""
            keyMessage = null
            keySuccess = false
            apiKey = ""
            isEditingKey = false
            hasKey = false
            return@LaunchedEffect
        }
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
        isEditingKey = false
        hasKey = false
    }

    LaunchedEffect(providerType, providerConfigurationInvalid) {
        hasKey = if (providerConfigurationInvalid) {
            false
        } else {
            withContext(Dispatchers.IO) {
                keyManager.getKeys(providerType).isNotEmpty()
            }
        }
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
        if (providerConfigurationInvalid) return
        val trimmed = apiKey.trim()
        if (trimmed.isBlank() || isTestingKey) return
        isTestingKey = true
        keyMessage = null
        keySuccess = false
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
                val stored = withContext(Dispatchers.IO) {
                    keyManager.addKey(trimmed, providerType)
                }
                keySuccess = stored
                if (stored) {
                    keyMessage = null
                    hasKey = true
                    isEditingKey = false
                    apiKey = ""
                } else {
                    keyMessage = keystoreError
                }
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
                CommandOutcome.Failure(onboardingTestFailed)
            }
            isTestingCommand = false
            when (outcome) {
                is CommandOutcome.Success -> testOutput = outcome.text
                is CommandOutcome.Unavailable -> testError = outcome.message
                is CommandOutcome.Failure -> testError = onboardingTestFailed
                is CommandOutcome.Refusal -> testError = onboardingTestFailed
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val rhythm = remember(maxWidth, maxHeight) {
            SlateRhythm.forSize(maxWidth, maxHeight)
        }
        val isCompact = maxWidth < 600.dp
        CompositionLocalProvider(LocalSlateRhythm provides rhythm) {
            Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (isCompact) {
                    OnboardingNavigation(
                        step = step,
                        enabled = when (step) {
                            1 -> hasKey || keySuccess
                            2 -> selectedModel.isNotBlank()
                            3 -> serviceEnabled
                            4 -> testOutput != null
                            0 -> !providerConfigurationInvalid
                            else -> true
                        },
                        onBack = { step = (step - 1).coerceAtLeast(0) },
                        onNext = {
                            if (step == 4) finishSetup(false) else step += 1
                        },
                        modifier = Modifier
                            .navigationBarsPadding()
                            .imePadding()
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                OnboardingHeader(step = step, totalSteps = 5)

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    AnimatedContent(
                        targetState = step,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .fillMaxHeight()
                            .widthIn(max = 760.dp),
                        transitionSpec = {
                            if (motion.reduceMotion) {
                                fadeIn(animationSpec = androidx.compose.animation.core.snap()) togetherWith
                                    fadeOut(animationSpec = androidx.compose.animation.core.snap())
                            } else {
                                val direction = if (targetState > initialState) 1 else -1
                                (slideInHorizontally(tween(300)) { direction * it / 5 } + fadeIn(tween(220))) togetherWith
                                    (slideOutHorizontally(tween(240)) { -direction * it / 5 } + fadeOut(tween(160)))
                            }
                        },
                        label = "onboarding_step"
                    ) { currentStep ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(horizontal = rhythm.screenPaddingH, vertical = 12.dp),
                            verticalArrangement = Arrangement.Top
                        ) {
                            OnboardingStepContent(
                                step = currentStep,
                                providerType = providerType,
                                providerLabel = providerLabel,
                                providerConfigurationInvalid = providerConfigurationInvalid,
                                selectedModel = selectedModel,
                                hasKey = hasKey,
                                isEditingKey = isEditingKey,
                                apiKey = apiKey,
                                isTestingKey = isTestingKey,
                                keystoreAvailable = keystoreAvailable,
                                keyMessage = keyMessage,
                                serviceEnabled = serviceEnabled,
                                testOutput = testOutput,
                                testError = testError,
                                isTestingCommand = isTestingCommand,
                                onProviderSelected = {
                                    providerType = it
                                    providerConfigurationInvalid = false
                                },
                                onModelSelected = {
                                    selectedModel = it
                                    prefs.edit().putString(config.modelPrefKey, it).apply()
                                },
                                onApiKeyChanged = {
                                    apiKey = it.take(256)
                                    keyMessage = null
                                    keySuccess = false
                                },
                                onEditKey = {
                                    isEditingKey = true
                                    apiKey = ""
                                    keyMessage = null
                                    keySuccess = false
                                },
                                onValidateKey = { validateAndStoreKey() },
                                onOpenAccessibility = {
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                onTestCommand = { testCommand() }
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            TextButton(
                                onClick = { finishSetup(true) },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            ) {
                                Text(stringResource(R.string.onboarding_later))
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }

                if (!isCompact) {
                    OnboardingNavigation(
                        step = step,
                        enabled = when (step) {
                            1 -> hasKey || keySuccess
                            2 -> selectedModel.isNotBlank()
                            3 -> serviceEnabled
                            4 -> testOutput != null
                            0 -> !providerConfigurationInvalid
                            else -> true
                        },
                        onBack = { step = (step - 1).coerceAtLeast(0) },
                        onNext = {
                            if (step == 4) finishSetup(false) else step += 1
                        }
                    )
                }
            }
        }
    }
}
}

@Composable
private fun OnboardingHeader(step: Int, totalSteps: Int) {
    val rhythm = LocalSlateRhythm.current
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .widthIn(max = 760.dp)
                .padding(horizontal = rhythm.screenPaddingH)
                .padding(top = 16.dp, bottom = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SlateMark(size = 44.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.onboarding_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.onboarding_progress, step + 1, totalSteps),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            LinearProgressIndicator(
                progress = { (step + 1).toFloat() / totalSteps.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun OnboardingNavigation(
    step: Int,
    enabled: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rhythm = LocalSlateRhythm.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        tonalElevation = 2.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .padding(horizontal = rhythm.screenPaddingH, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier
                            .weight(0.42f)
                            .heightIn(min = 52.dp)
                    ) {
                        Text(stringResource(R.string.onboarding_back), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Button(
                    onClick = onNext,
                    enabled = enabled,
                    modifier = if (step == 0) {
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    } else {
                        Modifier
                            .weight(0.58f)
                            .heightIn(min = 52.dp)
                    }
                ) {
                    Text(
                        text = stringResource(
                            if (step == 4) R.string.onboarding_finish else R.string.onboarding_next
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingStepContent(
    step: Int,
    providerType: String,
    providerLabel: String,
    providerConfigurationInvalid: Boolean,
    selectedModel: String,
    hasKey: Boolean,
    isEditingKey: Boolean,
    apiKey: String,
    isTestingKey: Boolean,
    keystoreAvailable: Boolean,
    keyMessage: String?,
    serviceEnabled: Boolean,
    testOutput: String?,
    testError: String?,
    isTestingCommand: Boolean,
    onProviderSelected: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onApiKeyChanged: (String) -> Unit,
    onEditKey: () -> Unit,
    onValidateKey: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onTestCommand: () -> Unit
) {
    when (step) {
        0 -> ProviderStep(
            providerType = providerType,
            providerConfigurationInvalid = providerConfigurationInvalid,
            onProviderSelected = onProviderSelected
        )
        1 -> ApiKeyStep(
            providerLabel = providerLabel,
            hasKey = hasKey,
            isEditingKey = isEditingKey,
            apiKey = apiKey,
            isTestingKey = isTestingKey,
            keystoreAvailable = keystoreAvailable,
            keyMessage = keyMessage,
            onApiKeyChanged = onApiKeyChanged,
            onEditKey = onEditKey,
            onValidateKey = onValidateKey
        )
        2 -> ModelStep(
            providerType = providerType,
            selectedModel = selectedModel,
            onModelSelected = onModelSelected
        )
        3 -> AccessibilityStep(
            serviceEnabled = serviceEnabled,
            onOpenAccessibility = onOpenAccessibility
        )
        else -> TestStep(
            testOutput = testOutput,
            testError = testError,
            isTestingCommand = isTestingCommand,
            onTestCommand = onTestCommand
        )
    }
}

@Composable
private fun ProviderStep(
    providerType: String,
    providerConfigurationInvalid: Boolean,
    onProviderSelected: (String) -> Unit
) {
    SlateCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(R.string.onboarding_provider_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.onboarding_provider_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (providerConfigurationInvalid) {
                Text(
                    text = stringResource(R.string.error_provider_selection_invalid),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Medium
                )
            }
            onboardingProviders.forEach { candidate ->
                val selected = candidate == providerType
                val label = when (candidate) {
                    ProviderType.OPENROUTER -> stringResource(R.string.settings_provider_openrouter)
                    ProviderType.DEEPSEEK -> stringResource(R.string.settings_provider_deepseek)
                    else -> stringResource(R.string.settings_provider_nvidia)
                }
                OnboardingChoiceCard(
                    label = label,
                    supporting = null,
                    badgeText = providerBadgeText(candidate),
                    selected = selected,
                    onClick = { onProviderSelected(candidate) }
                )
            }
        }
    }
}

@Composable
private fun ApiKeyStep(
    providerLabel: String,
    hasKey: Boolean,
    isEditingKey: Boolean,
    apiKey: String,
    isTestingKey: Boolean,
    keystoreAvailable: Boolean,
    keyMessage: String?,
    onApiKeyChanged: (String) -> Unit,
    onEditKey: () -> Unit,
    onValidateKey: () -> Unit
) {
    SlateCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.onboarding_key_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                stringResource(R.string.onboarding_key_message, providerLabel),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (hasKey && !isEditingKey) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.45f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.onboarding_key_saved_title),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                stringResource(R.string.onboarding_key_saved_message, providerLabel),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = onEditKey) {
                            Text(stringResource(R.string.onboarding_key_edit))
                        }
                    }
                }
            } else {
                SlateTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChanged,
                    label = { Text(stringResource(R.string.keys_api_key_label)) },
                    placeholder = { Text(stringResource(R.string.onboarding_key_placeholder)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = keyMessage != null
                )
                Button(
                    onClick = onValidateKey,
                    enabled = apiKey.isNotBlank() && !isTestingKey && keystoreAvailable,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                ) {
                    if (isTestingKey) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.onboarding_key_validating))
                    } else {
                        Text(stringResource(R.string.onboarding_key_validate))
                    }
                }
                keyMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelStep(
    providerType: String,
    selectedModel: String,
    onModelSelected: (String) -> Unit
) {
    SlateCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.onboarding_model_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                stringResource(R.string.onboarding_model_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            onboardingModels(providerType).forEach { candidate ->
                val brand = modelBrand(candidate, providerType)
                OnboardingChoiceCard(
                    label = candidate,
                    supporting = brand,
                    badgeText = brand,
                    selected = candidate == selectedModel,
                    onClick = { onModelSelected(candidate) },
                    allowLabelWrapping = true
                )
            }
        }
    }
}

@Composable
private fun AccessibilityStep(
    serviceEnabled: Boolean,
    onOpenAccessibility: () -> Unit
) {
    SlateCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.onboarding_accessibility_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                stringResource(R.string.onboarding_accessibility_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                onClick = onOpenAccessibility,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                Icon(Icons.Rounded.OpenInNew, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.onboarding_open_accessibility))
            }
            Text(
                if (serviceEnabled) {
                    stringResource(R.string.onboarding_accessibility_ready)
                } else {
                    stringResource(R.string.onboarding_accessibility_waiting)
                },
                color = if (serviceEnabled) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

@Composable
private fun TestStep(
    testOutput: String?,
    testError: String?,
    isTestingCommand: Boolean,
    onTestCommand: () -> Unit
) {
    SlateCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SlateMorphIcon(
                    type = SlateMorphIconType.LoadingSuccess,
                    toggled = testOutput != null,
                    tint = if (testOutput != null) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.onboarding_test_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                stringResource(R.string.onboarding_test_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onTestCommand,
                enabled = !isTestingCommand,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
            ) {
                if (isTestingCommand) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(stringResource(R.string.onboarding_test_button))
            }
            testOutput?.let {
                Text(it, color = MaterialTheme.colorScheme.tertiary)
            }
            testError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun OnboardingChoiceCard(
    label: String,
    supporting: String?,
    badgeText: String,
    selected: Boolean,
    onClick: () -> Unit,
    allowLabelWrapping: Boolean = false
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick
            ),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        },
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OnboardingBadge(text = badgeText)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                supporting?.let {
                    Text(
                        text = it,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = label,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    softWrap = allowLabelWrapping,
                    overflow = TextOverflow.Clip
                )
            }
            if (selected) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun OnboardingBadge(text: String) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text.take(2).uppercase(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun providerBadgeText(provider: String): String = when (provider) {
    ProviderType.OPENROUTER -> "OR"
    ProviderType.DEEPSEEK -> "DS"
    else -> "NV"
}

private fun modelBrand(model: String, provider: String): String = when {
    model.startsWith("meta/") -> "Meta"
    model.startsWith("mistralai/") -> "Mistral AI"
    model.startsWith("deepseek/") || model.startsWith("deepseek-") -> "DeepSeek"
    model.startsWith("qwen/") -> "Qwen"
    else -> when (provider) {
        ProviderType.OPENROUTER -> "OpenRouter"
        ProviderType.DEEPSEEK -> "DeepSeek"
        else -> "NVIDIA NIM"
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
