package com.jcversa.swiftslate.ui.components

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize

/**
 * The motion policy shared by every Compose entry point.
 *
 * SwiftSlate is an accessibility companion, so animations are not allowed to become a second
 * interaction barrier. Android exposes the animation scales as global settings; when either
 * spatial transitions or animator effects are disabled, we keep the final visual state but
 * remove travel, bounce and waiting time.
 */
@Immutable
data class SlateMotion(
    val reduceMotion: Boolean
) {
    fun spatialFloatSpec(): AnimationSpec<Float> = if (reduceMotion) {
        snap()
    } else {
        spring(
            dampingRatio = 0.82f,
            stiffness = Spring.StiffnessMediumLow
        )
    }

    fun expressiveFloatSpec(): AnimationSpec<Float> = if (reduceMotion) {
        snap()
    } else {
        spring(
            dampingRatio = 0.76f,
            stiffness = Spring.StiffnessLow
        )
    }

    fun effectFloatSpec(): AnimationSpec<Float> = if (reduceMotion) {
        snap()
    } else {
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )
    }

    fun transitionSpec(durationMillis: Int): FiniteAnimationSpec<Float> = if (reduceMotion) {
        snap()
    } else {
        tween(durationMillis)
    }

    fun sizeTransitionSpec(durationMillis: Int): FiniteAnimationSpec<IntSize> = if (reduceMotion) {
        snap()
    } else {
        tween(durationMillis)
    }
}

val LocalSlateMotion = staticCompositionLocalOf { SlateMotion(reduceMotion = false) }

/** Installs one live motion policy for the current Activity or preview. */
@Composable
fun SlateMotionProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var reduceMotion by remember(context) { mutableStateOf(animationsDisabled(context)) }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduceMotion = animationsDisabled(context)
            }
        }
        val animatorUri = Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)
        val transitionUri = Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE)
        resolver.registerContentObserver(animatorUri, false, observer)
        resolver.registerContentObserver(transitionUri, false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    CompositionLocalProvider(
        LocalSlateMotion provides SlateMotion(reduceMotion = reduceMotion),
        content = content
    )
}

private fun animationsDisabled(context: Context): Boolean {
    return try {
        val resolver = context.contentResolver
        val animatorScale = Settings.Global.getFloat(
            resolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        val transitionScale = Settings.Global.getFloat(
            resolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f
        )
        animatorScale == 0f || transitionScale == 0f
    } catch (_: Exception) {
        // A settings provider failure must never prevent SwiftSlate from rendering.
        false
    }
}
