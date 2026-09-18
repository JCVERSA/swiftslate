package com.jcversa.swiftslate.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.jcversa.swiftslate.R
import com.jcversa.swiftslate.SwiftSlateApp
import com.jcversa.swiftslate.api.GeminiClient
import com.jcversa.swiftslate.api.OpenAICompatibleClient
import com.jcversa.swiftslate.manager.CommandManager
import com.jcversa.swiftslate.service.BackgroundReliability
import com.jcversa.swiftslate.service.CommandOutcome
import com.jcversa.swiftslate.service.runTextCommand
import com.jcversa.swiftslate.service.runTextCommand
import com.jcversa.swiftslate.manager.KeyManager
import com.jcversa.swiftslate.manager.StatsManager
import com.jcversa.swiftslate.model.PrefKeys
import com.jcversa.swiftslate.model.ProviderType
import com.jcversa.swiftslate.provider.Providers
import com.jcversa.swiftslate.ui.components.LocalSlateRhythm
import com.jcversa.swiftslate.ui.components.AnimateEntrance
import com.jcversa.swiftslate.ui.components.SlateMorphIcon
import com.jcversa.swiftslate.ui.components.SlateMorphIconType
import com.jcversa.swiftslate.ui.components.SlateTextField
import com.jcversa.swiftslate.ui.components.SlateMark
import com.jcversa.swiftslate.ui.components.bounceClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Locale

private fun checkServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
    return enabledServices.any {
        it.resolveInfo.serviceInfo.packageName == context.packageName
    }
}

@SuppressLint("SoonBlockedPrivateApi")
private fun isServiceCrashed(context: Context): Boolean {
    return try {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val field = AccessibilityServiceInfo::class.java.getDeclaredField("crashed")
        am.getInstalledAccessibilityServiceList().any {
            try {
                it.resolveInfo.serviceInfo.packageName == context.packageName && field.getBoolean(it)
            } catch (_: Exception) {
                false
            }
        }
    } catch (_: Exception) {
        false
    }
}

private fun readCrashMarker(context: Context): Long =
    try {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getLong(SwiftSlateApp.PREF_SERVICE_DIED_AT, 0L)
    } catch (_: Exception) {
        0L
    }

private fun clearCrashMarker(context: Context) {
    try {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().remove(SwiftSlateApp.PREF_SERVICE_DIED_AT).apply()
    } catch (_: Exception) {
    }
}

private sealed interface DiagnosticState {
    data object Success : DiagnosticState
    data class Failure(val message: String) : DiagnosticState
}

private suspend fun runDashboardDiagnostic(
    context: Context,
    keyManager: KeyManager,
    geminiClient: GeminiClient,
    openAIClient: OpenAICompatibleClient
): DiagnosticState {
    return try {
        val outcome = withContext(Dispatchers.IO) {
            withTimeout(90_000L) {
                runTextCommand(
                    context.applicationContext,
                    keyManager,
                    geminiClient,
                    openAIClient,
                    context.getString(R.string.dashboard_diagnostic_prompt),
                    context.getString(R.string.dashboard_diagnostic_input)
                )
            }
        }
        when (outcome) {
            is CommandOutcome.Success -> DiagnosticState.Success
            is CommandOutcome.Refusal -> DiagnosticState.Failure(
                context.getString(R.string.dashboard_diagnostic_refused)
            )
            is CommandOutcome.Unavailable -> DiagnosticState.Failure(outcome.message)
            is CommandOutcome.Failure -> DiagnosticState.Failure(outcome.message)
        }
    } catch (_: Exception) {
        DiagnosticState.Failure(context.getString(R.string.dashboard_diagnostic_failed))
    }
}

@Composable
fun DashboardScreen(keyManager: KeyManager, commandManager: CommandManager, statsManager: StatsManager) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val diagnosticScope = rememberCoroutineScope()
    val diagnosticGeminiClient = remember { GeminiClient() }
    val diagnosticOpenAIClient = remember { OpenAICompatibleClient() }
    var diagnosticRunning by rememberSaveable { mutableStateOf(false) }
    var diagnosticResult by remember { mutableStateOf<DiagnosticState?>(null) }
    var isServiceEnabled by remember { mutableStateOf(checkServiceEnabled(context)) }
    var keyCount by remember { mutableIntStateOf(0) }
    var showKilledBanner by remember { mutableStateOf(false) }
    var privacyMode by remember {
        mutableStateOf(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(PrefKeys.PRIVACY_MODE, false)
        )
    }
    var showOnboardingReminder by remember {
        mutableStateOf(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("onboarding_reminder", false)
        )
    }

    // Stats state
    var monthlyRequests by remember { mutableIntStateOf(statsManager.monthlyRequests) }
    var favoriteCommand by remember { mutableStateOf(statsManager.favoriteCommand) }
    var dailyCounts by remember { mutableStateOf(statsManager.dailyCounts()) }

    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(context) {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val listener = AccessibilityManager.AccessibilityStateChangeListener {
            isServiceEnabled = checkServiceEnabled(context)
        }
        am.addAccessibilityStateChangeListener(listener)
        onDispose { am.removeAccessibilityStateChangeListener(listener) }
    }

    LaunchedEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val (newEnabled, newKeyCount, killed) = withContext(Dispatchers.IO) {
                val storedProviderType = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    .getString(PrefKeys.PROVIDER_TYPE, null)
                val providerType = ProviderType.storedOrNull(storedProviderType) ?: ProviderType.GEMINI
                Triple(
                    checkServiceEnabled(context),
                    if (storedProviderType != null && !ProviderType.isValid(storedProviderType)) {
                        0
                    } else {
                        keyManager.getKeys(providerType).size
                    },
                    readCrashMarker(context) > 0L || isServiceCrashed(context)
                )
            }
            isServiceEnabled = newEnabled
            keyCount = newKeyCount
            monthlyRequests = statsManager.monthlyRequests
            favoriteCommand = statsManager.favoriteCommand
            dailyCounts = statsManager.dailyCounts()
            showKilledBanner = killed
            privacyMode = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean(PrefKeys.PRIVACY_MODE, false)
            BackgroundReliability.refreshRecoveryNotification(context)
        }
    }

    val noData = stringResource(R.string.dashboard_no_data)
    val rhythm = LocalSlateRhythm.current
    val scrollState = rememberScrollState()
    val storedProviderType = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        .getString(PrefKeys.PROVIDER_TYPE, null)
    val providerConfigurationInvalid =
        storedProviderType != null && !ProviderType.isValid(storedProviderType)
    val activeProviderType = ProviderType.storedOrNull(storedProviderType) ?: ProviderType.GEMINI
    val activeProvider = Providers.forType(activeProviderType)
    val activeModel = if (providerConfigurationInvalid) {
        ""
    } else {
        activeProvider.sanitizeModel(
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getString(activeProvider.modelPrefKey, activeProvider.defaultModel)
        )
    }
    val activeModelLabel = if (activeModel.isBlank()) {
        stringResource(R.string.dashboard_configuration_not_set)
    } else {
        activeModel
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = rhythm.screenPaddingH, vertical = rhythm.screenPaddingV)
    ) {
        // Welcome Header
        // (No action button: the redesign's decorative one did nothing but vibrate.)
        AnimateEntrance(index = 0) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = rhythm.cardGap),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.14f),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SlateMark()
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.dashboard_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.dashboard_subtitle),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (providerConfigurationInvalid) {
            AnimateEntrance(index = 1) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = rhythm.cardGap),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.24f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(
                            text = stringResource(R.string.error_provider_selection_invalid),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        if (privacyMode) {
            AnimateEntrance(index = 1) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = rhythm.cardGap),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.22f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        Text(
                            text = stringResource(R.string.settings_privacy_enabled),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        if (showOnboardingReminder) {
            AnimateEntrance(index = 1) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = rhythm.cardGap),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_onboarding_reminder),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                showOnboardingReminder = false
                                context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                                    .edit().putBoolean("onboarding_reminder", false).apply()
                            }
                        ) {
                            Text(stringResource(R.string.dashboard_onboarding_dismiss), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Service Interrupted Banner
        if (showKilledBanner) {
            AnimateEntrance(index = 1) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = rhythm.cardGap),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.error.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Warning,
                                contentDescription = stringResource(R.string.dashboard_cd_alert),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.dashboard_service_killed_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.dashboard_service_killed_message),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    clearCrashMarker(context)
                                    showKilledBanner = false
                                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.heightIn(min = 48.dp)
                            ) {
                                Text(stringResource(R.string.service_enable), fontSize = 13.sp)
                            }
                            TextButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    clearCrashMarker(context)
                                    showKilledBanner = false
                                }
                            ) {
                                Text(
                                    text = stringResource(R.string.dashboard_service_killed_dismiss),
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Accessibility Service Engine Status Card (Glassmorphic Accent)
        val statusBg = if (isServiceEnabled) {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        }
        val statusBorder = if (isServiceEnabled) {
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f)
        } else {
            MaterialTheme.colorScheme.error.copy(alpha = 0.25f)
        }

        AnimateEntrance(index = 2) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = rhythm.cardGap),
                shape = RoundedCornerShape(20.dp),
                color = statusBg,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, statusBorder)
            ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(
                                if (isServiceEnabled) {
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f)
                                } else {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (isServiceEnabled) {
                                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.32f)
                                } else {
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.32f)
                                },
                                shape = CircleShape
                            )
                    ) {
                        // The state change is meaningful feedback, but it is not a continuous
                        // pulse: the morph only runs when Android reports a different service
                        // state and settles immediately in reduced-motion mode.
                        SlateMorphIcon(
                            type = SlateMorphIconType.LoadingSuccess,
                            toggled = isServiceEnabled,
                            tint = if (isServiceEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                            contentDescription = if (isServiceEnabled) {
                                stringResource(R.string.service_status_active)
                            } else {
                                stringResource(R.string.service_status_inactive)
                            },
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.dashboard_engine_status),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = if (isServiceEnabled) stringResource(R.string.service_status_active)
                            else stringResource(R.string.service_status_inactive),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isServiceEnabled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (!isServiceEnabled) {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.PowerSettingsNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.service_enable), fontSize = 13.sp)
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = stringResource(R.string.service_status_active),
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.dashboard_connected),
                            color = MaterialTheme.colorScheme.tertiary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

        // Configuration Control Center: status is local until the user explicitly runs a test.
        AnimateEntrance(index = 3) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = rhythm.cardGap),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.dashboard_configuration_title),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.dashboard_configuration_desc),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (diagnosticResult is DiagnosticState.Success) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = stringResource(R.string.dashboard_diagnostic_success),
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.dashboard_configuration_provider),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (providerConfigurationInvalid) {
                                        stringResource(R.string.error_provider_selection_invalid)
                                    } else {
                                        when (activeProviderType) {
                                            ProviderType.GROQ -> stringResource(R.string.settings_provider_groq)
                                            ProviderType.NVIDIA -> stringResource(R.string.settings_provider_nvidia)
                                            ProviderType.OPENROUTER -> stringResource(R.string.settings_provider_openrouter)
                                            ProviderType.DEEPSEEK -> stringResource(R.string.settings_provider_deepseek)
                                            ProviderType.CUSTOM -> stringResource(R.string.settings_provider_custom)
                                            else -> stringResource(R.string.settings_provider_gemini)
                                        }
                                    },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.dashboard_configuration_model),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = activeModelLabel,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    modifier = Modifier.widthIn(max = 220.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.dashboard_configuration_keys),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.dashboard_configuration_key_count, keyCount),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (keyCount > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (!diagnosticRunning) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                diagnosticRunning = true
                                diagnosticResult = null
                                diagnosticScope.launch {
                                    diagnosticResult = runDashboardDiagnostic(
                                        context,
                                        keyManager,
                                        diagnosticGeminiClient,
                                        diagnosticOpenAIClient
                                    )
                                    diagnosticRunning = false
                                }
                            }
                        },
                        enabled = !diagnosticRunning && !privacyMode && !providerConfigurationInvalid,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        if (diagnosticRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dashboard_diagnostic_running))
                        } else {
                            Icon(Icons.Rounded.NetworkCheck, null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.dashboard_diagnostic_button))
                        }
                    }
                    when (val result = diagnosticResult) {
                        is DiagnosticState.Success -> Text(
                            text = stringResource(R.string.dashboard_diagnostic_success),
                            color = MaterialTheme.colorScheme.tertiary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        is DiagnosticState.Failure -> Text(
                            text = result.message,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        null -> Unit
                    }
                }
            }
        }

        // Dual Side-by-Side Statistics Metrics Cards
        AnimateEntrance(index = 4) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = rhythm.cardGap),
                horizontalArrangement = Arrangement.spacedBy(rhythm.cardGap)
            ) {
                // Metric Card 1: Operations Count
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                    ),
                    tonalElevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                        RoundedCornerShape(10.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.TrendingUp,
                                    contentDescription = stringResource(R.string.dashboard_cd_requests),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.dashboard_metric_actions),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "$monthlyRequests",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.dashboard_monthly_requests),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Metric Card 2: Configured Keys
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                    ),
                    tonalElevation = 2.dp
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(
                                        if (keyCount > 0) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f),
                                        RoundedCornerShape(10.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Key,
                                    contentDescription = stringResource(R.string.dashboard_api_keys_title),
                                    tint = if (keyCount > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.dashboard_metric_integrations),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "$keyCount",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        // The count is already the headline above; a dedicated label beats
                        // slicing the formatted dashboard_keys_configured string apart.
                        Text(
                            text = stringResource(R.string.dashboard_metric_keys),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Brand Banner or Add Key Hint
        if (keyCount == 0) {
            AnimateEntrance(index = 4) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = rhythm.cardGap),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VpnKey,
                            contentDescription = stringResource(R.string.dashboard_cd_key_missing),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.dashboard_add_key_hint),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Productivity Odometer
        val totalReqs = statsManager.totalRequests
        // Rough estimate, honestly labeled as such below: ~2.5 min of typing saved per run.
        val minutesSaved = totalReqs * 2.5f
        val hoursSaved = minutesSaved / 60f
        val formattedTime = if (hoursSaved >= 1f) {
            stringResource(R.string.dashboard_odometer_hours, hoursSaved)
        } else {
            stringResource(R.string.dashboard_odometer_minutes, minutesSaved)
        }

        AnimateEntrance(index = 4) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = rhythm.cardGap),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                ),
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hoisted: drawBehind's DrawScope cannot read composition locals, and the
                    // arc must follow the theme (not a hardcoded indigo) in both modes.
                    val arcTrack = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                    val arcColor = MaterialTheme.colorScheme.primary
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .drawBehind {
                                drawArc(
                                    color = arcTrack,
                                    startAngle = 0f,
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    style = Stroke(width = 5.dp.toPx())
                                )
                                drawArc(
                                    color = arcColor,
                                    startAngle = -90f,
                                    sweepAngle = if (totalReqs > 0) 240f else 40f,
                                    useCenter = false,
                                    style = Stroke(
                                        width = 5.dp.toPx(),
                                        cap = StrokeCap.Round
                                    )
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.HourglassEmpty,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.dashboard_odometer_title),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formattedTime,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.dashboard_odometer_basis, totalReqs),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        // Interactive Sandbox Playground (simulated demo — no network, no AI call).
        val sandboxDemoInput = stringResource(R.string.dashboard_sandbox_demo_input)
        val sandboxDemoOutput = stringResource(R.string.dashboard_sandbox_demo_output)
        var sandboxText by remember(sandboxDemoInput) { mutableStateOf(sandboxDemoInput) }
        var isSandboxProcessing by remember { mutableStateOf(false) }
        var sandboxSuccess by remember { mutableStateOf(false) }

        LaunchedEffect(isSandboxProcessing) {
            if (isSandboxProcessing) {
                sandboxSuccess = false
                delay(1600L) // simulated "thinking" pause
                sandboxText = ""
                for (i in 1..sandboxDemoOutput.length) {
                    delay(30L)
                    sandboxText = sandboxDemoOutput.substring(0, i)
                }
                isSandboxProcessing = false
                sandboxSuccess = true
            }
        }

        AnimateEntrance(index = 5) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = rhythm.cardGap),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                ),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.dashboard_sandbox_title),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Box(
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.dashboard_sandbox_badge),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = stringResource(R.string.dashboard_sandbox_desc),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                RoundedCornerShape(14.dp)
                            )
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.12f),
                                RoundedCornerShape(14.dp)
                            )
                            .padding(14.dp)
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.dashboard_sandbox_editor),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                                if (isSandboxProcessing || sandboxSuccess) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        SlateMorphIcon(
                                            type = SlateMorphIconType.LoadingSuccess,
                                            toggled = sandboxSuccess,
                                            tint = if (sandboxSuccess) {
                                                MaterialTheme.colorScheme.tertiary
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Text(
                                            text = if (sandboxSuccess) {
                                                stringResource(R.string.dashboard_sandbox_replaced)
                                            } else {
                                                stringResource(R.string.dashboard_sandbox_replacing)
                                            },
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (sandboxSuccess) {
                                                MaterialTheme.colorScheme.tertiary
                                            } else {
                                                MaterialTheme.colorScheme.primary
                                            }
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            SlateTextField(
                                value = sandboxText,
                                onValueChange = { if (!isSandboxProcessing) sandboxText = it },
                                singleLine = false,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (!isSandboxProcessing) {
                                    isSandboxProcessing = true
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isSandboxProcessing,
                            modifier = Modifier
                                .weight(1.3f)
                                .heightIn(min = 48.dp)
                                .bounceClick(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.FlashOn,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.dashboard_sandbox_expand), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                if (!isSandboxProcessing) {
                                    sandboxText = sandboxDemoInput
                                    sandboxSuccess = false
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            enabled = !isSandboxProcessing,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .bounceClick()
                        ) {
                            Text(stringResource(R.string.dashboard_sandbox_reset), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // Usage Analytics Panel
        AnimateEntrance(index = 6) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                ),
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    // Header of panel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.BarChart,
                                contentDescription = stringResource(R.string.dashboard_cd_chart),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.dashboard_chart_title),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = stringResource(R.string.dashboard_last_7_days),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Favorite action row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.dashboard_favorite_command),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = favoriteCommand ?: noData,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Chart Container
                    val maxCount = dailyCounts.maxOfOrNull { it.second } ?: 0
                    val dayNameFmt = remember { SimpleDateFormat("EEE", Locale.getDefault()) }
                    val dateParseFmt = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
                    val gridLineColor = MaterialTheme.colorScheme.outline

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .drawBehind {
                                val strokeWidth = 1.dp.toPx()
                                val dashPathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), 0f)
                                val count = 3
                                for (i in 1..count) {
                                    val y = size.height * (i.toFloat() / (count + 1))
                                    drawLine(
                                        color = gridLineColor.copy(alpha = 0.2f),
                                        start = Offset(0f, y),
                                        end = Offset(size.width, y),
                                        strokeWidth = strokeWidth,
                                        pathEffect = dashPathEffect
                                    )
                                }
                            },
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        dailyCounts.forEach { (dateStr, count) ->
                            // parse() returns null on unparseable input — fall back to "?"
                            // instead of asserting non-null and relying on the catch.
                            val dayLabel = try {
                                dateParseFmt.parse(dateStr)?.let { date ->
                                    dayNameFmt.format(date).take(3).uppercase()
                                } ?: "?"
                            } catch (_: Exception) { "?" }

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                if (count > 0) {
                                    Text(
                                        text = "$count",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.BottomCenter
                                ) {
                                    val barGradient = Brush.verticalGradient(
                                        colors = listOf(
                                            MaterialTheme.colorScheme.primary,
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                        )
                                    )
                                    val barHeightFactor = if (maxCount > 0) {
                                        (count.toFloat() / maxCount).coerceAtLeast(if (count > 0) 0.08f else 0f)
                                    } else 0f

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .fillMaxHeight(barHeightFactor)
                                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                            .background(barGradient)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = dayLabel,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
