package com.jcversa.swiftslate

import android.Manifest
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
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
import com.jcversa.swiftslate.ui.CommandsScreen
import com.jcversa.swiftslate.ui.DashboardScreen
import com.jcversa.swiftslate.ui.KeysScreen
import com.jcversa.swiftslate.ui.OnboardingScreen
import com.jcversa.swiftslate.ui.SettingsScreen
import com.jcversa.swiftslate.model.PrefKeys
import com.jcversa.swiftslate.ui.components.LocalSlateMotion
import com.jcversa.swiftslate.ui.components.LocalSlateRhythm
import com.jcversa.swiftslate.ui.components.SlateRhythm
import com.jcversa.swiftslate.ui.theme.SwiftSlateTheme

enum class Tab(@param:StringRes val titleRes: Int, val icon: ImageVector) {
    Dashboard(R.string.dashboard_title, Icons.Default.Home),
    Keys(R.string.keys_title, Icons.Default.Lock),
    Commands(R.string.commands_title, Icons.AutoMirrored.Filled.List),
    Settings(R.string.settings_title, Icons.Default.Settings)
}

const val EXTRA_OPEN_SECURE_BACKUP = "com.jcversa.swiftslate.OPEN_SECURE_BACKUP"

class MainActivity : ComponentActivity() {
    private var openSecureBackupRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openSecureBackupRequest = intent.getBooleanExtra(EXTRA_OPEN_SECURE_BACKUP, false)
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
                    SwiftSlateMainScreen(
                        openSecureBackup = openSecureBackupRequest,
                        onSecureBackupRequestConsumed = { openSecureBackupRequest = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SECURE_BACKUP, false)) {
            openSecureBackupRequest = true
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
fun SwiftSlateMainScreen(
    vm: SwiftSlateViewModel = viewModel(),
    openSecureBackup: Boolean = false,
    onSecureBackupRequestConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val motion = LocalSlateMotion.current
    var selectedTab by rememberSaveable {
        mutableStateOf(if (openSecureBackup) Tab.Settings else Tab.Dashboard)
    }

    LaunchedEffect(openSecureBackup) {
        if (openSecureBackup) {
            selectedTab = Tab.Settings
            onSecureBackupRequestConsumed()
        }
    }

    // The opening motion is shared by every form factor and becomes instantaneous when Android
    // asks the app to reduce motion.
    val introProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit, motion.reduceMotion) {
        introProgress.animateTo(
            targetValue = 1f,
            animationSpec = motion.expressiveFloatSpec()
        )
    }

    // Request notification permission on first launch (Android 13+).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit().putBoolean("notification_permission_requested", true).apply()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                if (!prefs.getBoolean("notification_permission_requested", false)) {
                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } catch (_: Exception) {
                // A corrupted pref must not crash the activity that shares a process with the
                // accessibility service.
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val windowLayout = remember(maxWidth) { SlateWindowLayout.fromWidth(maxWidth) }
        val isCompact = windowLayout == SlateWindowLayout.Compact
        val isExpanded = windowLayout == SlateWindowLayout.Expanded

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
                if (isCompact) {
                    SwiftSlateCompactNavigation(
                        selectedTab = selectedTab,
                        onSelect = { selectedTab = it }
                    )
                }
            }
        ) { innerPadding ->
            val screens = remember(openSecureBackup) {
                Tab.entries.associateWith { tab ->
                    movableContentOf {
                        when (tab) {
                            Tab.Dashboard -> DashboardScreen(vm.keyManager, vm.commandManager, vm.statsManager)
                            Tab.Keys -> KeysScreen(vm.keyManager, vm.prefs)
                            Tab.Commands -> CommandsScreen(vm.commandManager)
                            Tab.Settings -> SettingsScreen(
                                commandManager = vm.commandManager,
                                prefs = vm.prefs,
                                keyManager = vm.keyManager,
                                openSecureBackup = openSecureBackup,
                                onSecureBackupRequestConsumed = onSecureBackupRequestConsumed
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isCompact) {
                    SwiftSlateNavigationRail(
                        selectedTab = selectedTab,
                        expanded = isExpanded,
                        onSelect = { selectedTab = it }
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = if (isCompact) 0.dp else 12.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    AnimatedContent(
                    targetState = selectedTab,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 1120.dp),
                    transitionSpec = {
                        if (motion.reduceMotion) {
                            fadeIn(animationSpec = androidx.compose.animation.core.snap()) togetherWith
                                fadeOut(animationSpec = androidx.compose.animation.core.snap())
                        } else {
                            val direction = if (targetState.ordinal > initialState.ordinal)
                                AnimatedContentTransitionScope.SlideDirection.Left
                            else
                                AnimatedContentTransitionScope.SlideDirection.Right
                            val duration = if (isCompact) 220 else 260
                            val tabEase = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
                            (slideIntoContainer(direction, tween(duration, easing = tabEase)) + fadeIn(tween(duration))) togetherWith
                                (slideOutOfContainer(direction, tween(duration, easing = tabEase)) + fadeOut(tween(duration)))
                        }
                    },
                    label = "tab_transition"
                ) { tab ->
                    // A single rhythm is still shared by every destination, while its available
                    // width now grows naturally beside the rail on larger windows.
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val rhythm = remember(maxWidth, maxHeight) {
                            SlateRhythm.forSize(maxWidth, maxHeight)
                        }
                        CompositionLocalProvider(LocalSlateRhythm provides rhythm) {
                            screens[tab]?.invoke()
                        }
                    }
                }
                }
            }
        }
    }
}

@Composable
fun SwiftSlateSplashScreen(onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalSlateMotion.current
    val scale = remember { androidx.compose.animation.core.Animatable(0.7f) }
    val pathProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    val glowRadius = remember { androidx.compose.animation.core.Animatable(0f) }
    val textAlpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val dismissProgress = remember { androidx.compose.animation.core.Animatable(1f) }

    LaunchedEffect(Unit, motion.reduceMotion) {
        if (motion.reduceMotion) {
            // Reduced motion keeps the same brand mark and content, but does not make the user
            // wait through a decorative sequence before reaching the workspace.
            scale.snapTo(1f)
            pathProgress.snapTo(1f)
            glowRadius.snapTo(150f)
            textAlpha.snapTo(1f)
            dismissProgress.snapTo(0f)
            onDismiss()
            return@LaunchedEffect
        }

        // Step 1: Scale/bounce in the central terminal node
        scale.animateTo(targetValue = 1f, animationSpec = motion.expressiveFloatSpec())
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
