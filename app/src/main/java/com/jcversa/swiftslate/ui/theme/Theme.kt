package com.jcversa.swiftslate.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.view.WindowCompat

/**
 * A quiet slate-and-mint palette. Dynamic Material 3 color remains available as
 * an opt-in for future adaptive themes; SwiftSlate currently keeps this calm,
 * on-brand palette consistent across devices.
 */
private val DarkColorScheme = darkColorScheme(
    background = Color(0xFF0C1217),
    surface = Color(0xFF141D23),
    surfaceVariant = Color(0xFF1B2830),
    surfaceContainerHigh = Color(0xFF24343B),
    onBackground = Color(0xFFF2F7F5),
    onSurface = Color(0xFFF2F7F5),
    onSurfaceVariant = Color(0xFFA7B6B3),
    outline = Color(0xFF405158),
    primary = Color(0xFF9ADBC6),
    onPrimary = Color(0xFF07352D),
    primaryContainer = Color(0xFF205146),
    onPrimaryContainer = Color(0xFFC6F1E3),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFFB7DDBA),
    onTertiary = Color(0xFF203725),
    tertiaryContainer = Color(0xFF364D38),
    onTertiaryContainer = Color(0xFFD3F2D0)
)

private val LightColorScheme = lightColorScheme(
    background = Color(0xFFF7F9F6),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFEDF3EF),
    surfaceContainerHigh = Color(0xFFE0EBE5),
    onBackground = Color(0xFF17211E),
    onSurface = Color(0xFF17211E),
    onSurfaceVariant = Color(0xFF5E706B),
    outline = Color(0xFF9BAEA8),
    primary = Color(0xFF176B59),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC5EBDD),
    onPrimaryContainer = Color(0xFF073D31),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    tertiary = Color(0xFF39734A),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEFC0),
    onTertiaryContainer = Color(0xFF0E3A1A)
)

private val SwiftSlateShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

private val BaseTypography = Typography()

private val SwiftSlateTypography = BaseTypography.copy(
    displaySmall = BaseTypography.displaySmall.copy(
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.4).sp
    ),
    headlineMedium = BaseTypography.headlineMedium.copy(
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = BaseTypography.titleLarge.copy(
        fontWeight = FontWeight.Bold
    ),
    labelLarge = BaseTypography.labelLarge.copy(
        fontWeight = FontWeight.SemiBold
    )
)

@Composable
fun SwiftSlateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
            dynamicDarkColorScheme(context)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(activity.window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = SwiftSlateTypography,
        shapes = SwiftSlateShapes,
        content = content
    )
}
