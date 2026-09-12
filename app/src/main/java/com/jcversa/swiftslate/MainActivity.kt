package com.jcversa.swiftslate

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.Canvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.EaseOutQuart
import kotlinx.coroutines.delay
import com.jcversa.swiftslate.ui.components.bounceClick
import com.jcversa.swiftslate.ui.CommandsScreen
import com.jcversa.swiftslate.ui.DashboardScreen
import com.jcversa.swiftslate.ui.KeysScreen
import com.jcversa.swiftslate.ui.OnboardingScreen
import com.jcversa.swiftslate.ui.SettingsScreen
import com.jcversa.swiftslate.model.PrefKeys
import com.jcversa.swiftslate.ui.components.LocalSlateRhythm
import com.jcversa.swiftslate.ui.components.SlateRhythm
import com.jcversa.swiftslate.ui.theme.SwiftSlateTheme

enum class Tab(@param:StringRes val titleRes: Int, val icon: ImageVector) {
    Dashboard(R.string.dashboard_title, Icons.Default.Home),
    Keys(R.string.keys_title, Icons.Default.Lock),
    Commands(R.string.commands_title, Icons.AutoMirrored.Filled.List),
    Settings(R.string.settings_title, Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var showSplash by rememberSaveable { mutableStateOf(true) }
            val settingsPrefs = remember { getSharedPreferences("settings", Context.MODE_PRIVATE) }
            var showOnboarding by rememberSaveable {
                mutableStateOf(shouldShowFirstRunAssistant(this@MainActivity))
            }
            SwiftSlateTheme(dynamicColor = false) {
                if (showSplash) {
                    SwiftSlateSplashScreen(onDismiss = { showSplash = false })
                } else if (showOnboarding) {
                    OnboardingScreen(
                        prefs = settingsPrefs,
                        keyManager = (application as SwiftSlateApp).keyManager,
                        onComplete = { showOnboarding = false }
                    )
                } else {
                    SwiftSlateMainScreen()
                }
            }
        }
    }
}

/**
 * Old installs should not be interrupted by the new first-run walkthrough. Their existing
 * settings, command store, or encrypted key store are enough evidence that setup already ran.
 */
private fun shouldShowFirstRunAssistant(context: Context): Boolean {
    val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    if (settings.getBoolean(PrefKeys.ONBOARDING_COMPLETED, false)) return false

    val hasExistingAppData = sequenceOf("commands", "secure_keys_prefs")
        .map { context.getSharedPreferences(it, Context.MODE_PRIVATE) }
        .any { it.all.isNotEmpty() } || settings.all.isNotEmpty()
    if (hasExistingAppData) {
        settings.edit().putBoolean(PrefKeys.ONBOARDING_COMPLETED, true).apply()
        return false
    }
    return true
}

@Composable
fun SwiftSlateMainScreen(vm: SwiftSlateViewModel = viewModel()) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var selectedTab by rememberSaveable { mutableStateOf(Tab.Dashboard) }

    // Start-up app-opening fluid entrance animation
    val introProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        introProgress.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = 0.85f, // Sweet, subtle physical springiness
                stiffness = Spring.StiffnessLow
            )
        )
    }

    // Request notification permission on first launch (Android 13+)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> // Result not needed — we just need to prompt once
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("notification_permission_requested", true).apply()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                val alreadyRequested = prefs.getBoolean("notification_permission_requested", false)
                if (!alreadyRequested) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } catch (_: Exception) {
                // A corrupted pref must not crash this activity — it shares the process with
                // the accessibility service (#125).
            }
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = introProgress.value
                translationY = (1f - introProgress.value) * 32.dp.toPx()
                val scale = 0.96f + (0.04f * introProgress.value)
                scaleX = scale
                scaleY = scale
            },
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                ),
                shadowElevation = 6.dp,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Tab.entries.forEach { tab ->
                        val isSelected = selectedTab == tab
                        val backgroundAlpha by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (isSelected) 1f else 0f,
                            animationSpec = spring(
                                dampingRatio = 0.9f,
                                stiffness = Spring.StiffnessMediumLow
                            ),
                            label = "tab_background"
                        )
                        val iconColor = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val containerColor = MaterialTheme.colorScheme.primaryContainer

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(20.dp))
                                .background(containerColor.copy(alpha = backgroundAlpha))
                                .bounceClick {
                                    if (selectedTab != tab) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedTab = tab
                                    }
                                }
                                .padding(horizontal = 2.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Icon(
                                    imageVector = tab.icon,
                                    contentDescription = null,
                                    tint = iconColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = stringResource(tab.titleRes),
                                    color = iconColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        val screens = remember {
            Tab.entries.associateWith { tab ->
                movableContentOf {
                    when (tab) {
                        Tab.Dashboard -> DashboardScreen(vm.keyManager, vm.commandManager, vm.statsManager)
                        Tab.Keys -> KeysScreen(vm.keyManager, vm.prefs)
                        Tab.Commands -> CommandsScreen(vm.commandManager)
                        Tab.Settings -> SettingsScreen(vm.commandManager, vm.prefs, vm.keyManager)
                    }
                }
            }
        }

        AnimatedContent(
            targetState = selectedTab,
            modifier = Modifier.padding(innerPadding),
            transitionSpec = {
                val direction = if (targetState.ordinal > initialState.ordinal)
                    AnimatedContentTransitionScope.SlideDirection.Left
                else
                    AnimatedContentTransitionScope.SlideDirection.Right
                // Strong ease-out: the destination becomes readable immediately, then
                // settles quickly instead of making tab navigation feel like a carousel.
                val duration = 220
                val tabEase = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
                (slideIntoContainer(direction, tween(duration, easing = tabEase)) + fadeIn(tween(duration))) togetherWith
                    (slideOutOfContainer(direction, tween(duration, easing = tabEase)) + fadeOut(tween(duration)))
            },
            label = "tab_transition"
        ) { tab ->
            // One rhythm for every tab, derived from the height the content area
            // actually has after the nav bar and system insets. Resolving it here
            // rather than per screen is what keeps padding, gaps and type identical
            // across Dashboard, Keys, Commands and Settings.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val rhythm = remember(maxHeight) { SlateRhythm.forHeight(maxHeight) }
                CompositionLocalProvider(LocalSlateRhythm provides rhythm) {
                    screens[tab]?.invoke()
                }
            }
        }
    }
}

@Composable
fun SwiftSlateSplashScreen(onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val scale = remember { androidx.compose.animation.core.Animatable(0.7f) }
    val pathProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    val glowRadius = remember { androidx.compose.animation.core.Animatable(0f) }
    val textAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val dismissProgress = remember { androidx.compose.animation.core.Animatable(1f) }

    LaunchedEffect(Unit) {
        // Step 1: Scale/bounce in the central terminal node
        scale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        )
        // Step 2: Draw the terminal symbol outline
        pathProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(900, easing = EaseOutQuart)
        )
        // Step 3: Radiate glowing backdrop wave
        glowRadius.animateTo(
            targetValue = 150f,
            animationSpec = tween(500, easing = FastOutSlowInEasing)
        )
        // Step 4: Fade in branding text
        textAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(400)
        )
        // Wait for aesthetic flow
        delay(700)
        // Step 5: Slide up and fade out into the active workspace
        dismissProgress.animateTo(
            targetValue = 0f,
            animationSpec = tween(500, easing = EaseOutQuart)
        )
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .graphicsLayer {
                alpha = dismissProgress.value
                scaleX = 0.96f + (0.04f * dismissProgress.value)
                scaleY = 0.96f + (0.04f * dismissProgress.value)
                translationY = - (1f - dismissProgress.value) * 120f
            },
        contentAlignment = Alignment.Center
    ) {
        // Canvas-based premium vector drawing
        Canvas(
            modifier = Modifier
                .size(240.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
        ) {
            val width = size.width
            val height = size.height
            val centerX = width / 2
            val centerY = height / 2

            // Draw glowing backdrop blur circles
            if (glowRadius.value > 0f) {
                drawCircle(
                    color = colors.primary.copy(alpha = 0.12f * (1f - glowRadius.value / 150f)),
                    radius = glowRadius.value * 2f,
                    center = androidx.compose.ui.geometry.Offset(centerX, centerY)
                )
            }

            // Draw Slate Terminal Prompt symbol ">"
            val path = Path().apply {
                moveTo(centerX - 35f, centerY - 25f)
                lineTo(centerX - 5f, centerY)
                lineTo(centerX - 35f, centerY + 25f)
            }

            drawPath(
                path = path,
                color = colors.primary,
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            )

            // Draw typing block prompt "_"
            if (pathProgress.value > 0.4f) {
                val blockProgress = (pathProgress.value - 0.4f) / 0.6f
                val blockStartX = centerX + 15f
                val blockEndX = blockStartX + (35f * blockProgress)
                drawLine(
                    color = colors.primaryContainer,
                    start = androidx.compose.ui.geometry.Offset(blockStartX, centerY + 25f),
                    end = androidx.compose.ui.geometry.Offset(blockEndX, centerY + 25f),
                    strokeWidth = 6.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
        }

        // Branding Title
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 90.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "SWIFTSLATE",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = colors.onBackground,
                letterSpacing = 6.sp,
                modifier = Modifier.graphicsLayer { alpha = textAlpha.value }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "AI ACCESSIBILITY COMPANION",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = colors.onSurfaceVariant,
                letterSpacing = 2.sp,
                modifier = Modifier.graphicsLayer { alpha = textAlpha.value }
            )
        }
    }
}
