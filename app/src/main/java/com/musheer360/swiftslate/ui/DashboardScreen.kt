package com.musheer360.swiftslate.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.SwiftSlateApp
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.manager.KeyManager
import com.musheer360.swiftslate.manager.StatsManager
import com.musheer360.swiftslate.ui.components.LocalSlateRhythm
import com.musheer360.swiftslate.ui.components.AnimateEntrance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

@Composable
fun DashboardScreen(keyManager: KeyManager, commandManager: CommandManager, statsManager: StatsManager) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var isServiceEnabled by remember { mutableStateOf(checkServiceEnabled(context)) }
    var keyCount by remember { mutableIntStateOf(0) }
    var showKilledBanner by remember { mutableStateOf(false) }

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
                Triple(
                    checkServiceEnabled(context),
                    keyManager.getKeys().size,
                    readCrashMarker(context) > 0L || isServiceCrashed(context)
                )
            }
            isServiceEnabled = newEnabled
            keyCount = newKeyCount
            monthlyRequests = statsManager.monthlyRequests
            favoriteCommand = statsManager.favoriteCommand
            dailyCounts = statsManager.dailyCounts()
            showKilledBanner = killed
        }
    }

    val noData = stringResource(R.string.dashboard_no_data)
    val rhythm = LocalSlateRhythm.current
    val scrollState = rememberScrollState()

    // Pulsing animation for status indicators
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 2.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = EaseOutQuad),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = EaseOutQuad),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = rhythm.screenPaddingH, vertical = rhythm.screenPaddingV)
    ) {
        // Welcome Header
        // (No action button: the redesign's decorative one did nothing but vibrate.)
        AnimateEntrance(index = 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = rhythm.cardGap)
            ) {
                Text(
                    text = stringResource(R.string.dashboard_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.dashboard_subtitle),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                                modifier = Modifier.heightIn(min = 40.dp)
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
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer {
                                    scaleX = pulseScale
                                    scaleY = pulseScale
                                    alpha = pulseAlpha
                                }
                                .clip(CircleShape)
                                .background(
                                    if (isServiceEnabled) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.error
                                )
                        )
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isServiceEnabled) MaterialTheme.colorScheme.tertiary
                                    else MaterialTheme.colorScheme.error
                                )
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
                        modifier = Modifier.heightIn(min = 40.dp)
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

        // Dual Side-by-Side Statistics Metrics Cards
        AnimateEntrance(index = 3) {
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

        // Usage Analytics Panel
        AnimateEntrance(index = 5) {
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
