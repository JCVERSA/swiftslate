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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import com.jcversa.swiftslate.ui.components.bounceClick
import com.jcversa.swiftslate.ui.CommandsScreen
import com.jcversa.swiftslate.ui.DashboardScreen
import com.jcversa.swiftslate.ui.KeysScreen
import com.jcversa.swiftslate.ui.SettingsScreen
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
            SwiftSlateTheme {
                SwiftSlateMainScreen()
            }
        }
    }
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
                    .padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                border = androidx.compose.foundation.BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                ),
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp, horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Tab.entries.forEach { tab ->
                        val isSelected = selectedTab == tab
                        val backgroundAlpha by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (isSelected) 1f else 0f,
                            animationSpec = tween(250),
                            label = "tab_bg_alpha"
                        )
                        val iconColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        val containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(containerColor.copy(alpha = if (isSelected) backgroundAlpha else 0f))
                                .bounceClick {
                                    if (selectedTab != tab) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedTab = tab
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = stringResource(tab.titleRes),
                                tint = iconColor,
                                modifier = Modifier.size(22.dp)
                            )
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
                val duration = 300
                (slideIntoContainer(direction, tween(duration, easing = FastOutSlowInEasing)) + fadeIn(tween(duration))) togetherWith
                    (slideOutOfContainer(direction, tween(duration, easing = FastOutSlowInEasing)) + fadeOut(tween(duration)))
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
