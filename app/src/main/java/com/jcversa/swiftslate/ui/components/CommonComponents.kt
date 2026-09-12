package com.jcversa.swiftslate.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.EaseOutQuad
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color

/**
 * A satisfy-by-touch physically animated click modifier.
 * Scales down slightly when pressed and springs back when released.
 */
@Composable
fun Modifier.bounceClick(onClick: () -> Unit = {}): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "bounce"
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
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
    val visible = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * 60L) // cascading 60ms delay
        visible.value = true
    }
    AnimatedVisibility(
        visible = visible.value,
        enter = fadeIn(animationSpec = tween(400, easing = EaseOutQuad)) +
                slideInVertically(animationSpec = tween(400, easing = EaseOutQuad)) { it / 4 },
        exit = fadeOut(animationSpec = tween(200))
    ) {
        content()
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
        shadowElevation = 2.dp,
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
