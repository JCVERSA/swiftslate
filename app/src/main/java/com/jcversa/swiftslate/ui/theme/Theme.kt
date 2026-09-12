package com.jcversa.swiftslate.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.core.view.WindowCompat
import com.jcversa.swiftslate.ui.components.SlateMotionProvider

/**
 * SwiftSlate's visual signature is Sparkle Lavender: a luminous, slightly violet accent
 * that reads as polished against AMOLED black without turning the interface neon.
 *
 * The dark scheme deliberately keeps only the app backdrop pure black. Material surfaces stay
 * subtly tinted and become lighter at higher hierarchy levels, preserving separation without
 * sacrificing the OLED battery benefit of the original SwiftSlate theme.
 *
 * Dynamic color remains available as an explicit opt-in, but the product palette is the default
 * so the brand and contrast stay stable across devices.
 */
private val DarkColorScheme = darkColorScheme(
    // AMOLED foundation: pure black is reserved for the page backdrop.
    background = Color(0xFF000000),
    surface = Color(0xFF0C0B10),
    surfaceVariant = Color(0xFF17141E),
    surfaceContainerHigh = Color(0xFF211B2D),
    onBackground = Color(0xFFF7F2FC),
    onSurface = Color(0xFFF7F2FC),
    onSurfaceVariant = Color(0xFFC0B7C9),
    outline = Color(0xFF635B6D),

    // Sparkle Lavender — the primary brand/accent color.
    primary = Color(0xFFD9C7FF),
    onPrimary = Color(0xFF2E1A57),
    primaryContainer = Color(0xFF4A3374),
    onPrimaryContainer = Color(0xFFF1E8FF),

    // A soft opal-rose companion for secondary Material roles.
    secondary = Color(0xFFF2BEDC),
    onSecondary = Color(0xFF442036),
    secondaryContainer = Color(0xFF5A314A),
    onSecondaryContainer = Color(0xFFFFD9E9),

    // Mint stays reserved for healthy/active states and complements the violet accent.
    tertiary = Color(0xFF9FE8C4),
    onTertiary = Color(0xFF073A27),
    tertiaryContainer = Color(0xFF205A45),
    onTertiaryContainer = Color(0xFFBCF6D8),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val LightColorScheme = lightColorScheme(
    // The same sparkle identity remains legible in the light variant.
    background = Color(0xFFFCFAFF),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF1ECF5),
    surfaceContainerHigh = Color(0xFFE6DFEC),
    onBackground = Color(0xFF1D1924),
    onSurface = Color(0xFF1D1924),
    onSurfaceVariant = Color(0xFF635C6B),
    outline = Color(0xFF817889),

    primary = Color(0xFF6550A5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF7E526B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFD9E8),
    onSecondaryContainer = Color(0xFF31101F),
    tertiary = Color(0xFF356B50),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEFCF),
    onTertiaryContainer = Color(0xFF002113),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
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
    dynamicColor: Boolean = false,
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
        shapes = SwiftSlateShapes
    ) {
        SlateMotionProvider(content)
    }
}
