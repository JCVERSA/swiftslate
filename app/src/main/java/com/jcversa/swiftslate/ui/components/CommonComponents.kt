package com.jcversa.swiftslate.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import android.view.SoundEffectConstants
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset

/**
 * A restrained press-feedback click modifier.
 * Scales down slightly when pressed and returns immediately when released.
 * The legacy name is kept so existing screens stay source-compatible.
 */
@Composable
fun Modifier.bounceClick(onClick: () -> Unit = {}): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val motion = LocalSlateMotion.current
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val scale by animateFloatAsState(
        // A restrained 3% press confirms the tap without making the whole control jump.
        targetValue = if (isPressed && !motion.reduceMotion) 0.97f else 1f,
        animationSpec = motion.effectFloatSpec(),
        label = "press_scale"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                // Feedback is deliberately confined to controls that opt into bounceClick. This
                // gives the expressive UI a tactile and audible confirmation without making every
                // list row or passive state change noisy.
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                view.playSoundEffect(SoundEffectConstants.CLICK)
                onClick()
            }
        )
}

/**
 * Staggered entrance animation wrapper.
 * Delays entrance based on [index] to create an elegant cascade effect.
 */
@Composable
fun AnimateEntrance(
    index: Int,
    content: @Composable () -> Unit
) {
    val motion = LocalSlateMotion.current
    val visible = remember { mutableStateOf(false) }
    LaunchedEffect(Unit, motion.reduceMotion) {
        // A small stagger adds spatial order without turning every tab change into a show. It is
        // removed entirely when Android asks the app to reduce motion.
        if (!motion.reduceMotion) delay(index * 45L)
        visible.value = true
    }
    AnimatedVisibility(
        visible = visible.value,
        enter = if (motion.reduceMotion) {
            fadeIn(animationSpec = motion.transitionSpec(0))
        } else {
            fadeIn(animationSpec = tween(220, easing = EaseOutQuad)) +
                slideInVertically(animationSpec = tween(220, easing = EaseOutQuad)) { 12 } +
                scaleIn(initialScale = 0.97f, animationSpec = tween(220, easing = EaseOutQuad))
        },
        exit = fadeOut(animationSpec = motion.transitionSpec(120))
    ) {
        content()
    }
}

/**
 * The small terminal mark used as SwiftSlate's visual signature.
 * It is intentionally drawn from theme roles so it stays calm in both modes.
 */
@Composable
fun SlateMark(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp
) {
    val markShape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.size(size),
        shape = markShape,
        color = colors.primaryContainer,
        tonalElevation = 1.dp
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(size * 0.24f)) {
            val canvasWidth = this.size.width
            val canvasHeight = this.size.height
            val centerY = canvasHeight / 2f
            val left = canvasWidth * 0.14f
            val elbow = canvasWidth * 0.40f
            val right = canvasWidth * 0.14f
            drawPath(
                path = Path().apply {
                    moveTo(left, centerY - canvasHeight * 0.24f)
                    lineTo(elbow, centerY)
                    lineTo(left, centerY + canvasHeight * 0.24f)
                },
                color = colors.primary,
                style = Stroke(width = this.size.minDimension * 0.12f, cap = StrokeCap.Round)
            )
            drawLine(
                color = colors.primary,
                start = Offset(elbow + right * 0.28f, centerY + canvasHeight * 0.24f),
                end = Offset(canvasWidth - right, centerY + canvasHeight * 0.24f),
                strokeWidth = this.size.minDimension * 0.12f,
                cap = StrokeCap.Round
            )
        }
    }
}

/**
 * @param contentPadding inner padding. Defaults to the shared [SlateRhythm] so every
 *   card on every tab agrees; pass a value only to deliberately deviate.
 * @param verticalArrangement how children are distributed. Only meaningful together
 *   with [fillHeight].
 */
@Composable
fun SlateCard(
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    contentPadding: Dp = LocalSlateRhythm.current.cardPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit
) {
    val cardShape = MaterialTheme.shapes.large
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f),
                shape = cardShape
            ),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surface,
        // On AMOLED, tonal separation and a hairline are calmer than a grey shadow halo.
        shadowElevation = 0.dp,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
            verticalArrangement = verticalArrangement,
            content = content
        )
    }
}

/**
 * The page heading. Size and the gap below it come from the shared [SlateRhythm], so
 * all four tabs start at the same baseline and their first cards line up.
 */
@Composable
fun ScreenTitle(
    title: String,
    fontSize: TextUnit = LocalSlateRhythm.current.titleSize,
    bottomPadding: Dp = LocalSlateRhythm.current.titleGap
) {
    Text(
        text = title,
        fontSize = fontSize,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = bottomPadding)
    )
}

@Composable
fun SlateTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    singleLine: Boolean = true,
    readOnly: Boolean = false,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        singleLine = singleLine,
        readOnly = readOnly,
        isError = isError,
        visualTransformation = visualTransformation,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
            focusedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
            errorBorderColor = MaterialTheme.colorScheme.error,
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@Composable
fun SlateDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    )
}

@Composable
fun SlateItemCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = LocalSlateRhythm.current.itemPadding,
    content: @Composable RowScope.() -> Unit
) {
    val itemShape = MaterialTheme.shapes.small
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f),
                shape = itemShape
            ),
        shape = itemShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    ) {
        Row(
            modifier = Modifier.padding(contentPadding),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            content = content
        )
    }
}
