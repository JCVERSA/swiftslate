package com.jcversa.swiftslate

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcversa.swiftslate.ui.components.LocalSlateMotion
import com.jcversa.swiftslate.ui.components.SlateMark
import com.jcversa.swiftslate.ui.components.bounceClick

/** Window-level layout decisions, based on the space allocated to the app rather than the device. */
enum class SlateWindowLayout {
    Compact,
    Medium,
    Expanded;

    companion object {
        fun fromWidth(width: Dp): SlateWindowLayout = when {
            width < 600.dp -> Compact
            width < 840.dp -> Medium
            else -> Expanded
        }
    }
}

@Composable
fun SwiftSlateCompactNavigation(
    selectedTab: Tab,
    onSelect: (Tab) -> Unit
) {
    val motion = LocalSlateMotion.current
    val selectedIndex = Tab.entries.indexOf(selectedTab).coerceAtLeast(0)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)
        ),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 5.dp)
                .height(56.dp)
        ) {
            val gap = 4.dp
            val itemWidth = (maxWidth - gap * (Tab.entries.size - 1)) / Tab.entries.size
            val targetOffset = (itemWidth + gap) * selectedIndex
            val indicatorOffset by animateDpAsState(
                targetValue = targetOffset,
                animationSpec = if (motion.reduceMotion) snap() else tween(220),
                label = "compact_navigation_indicator"
            )

            // One shared indicator travels between destinations instead of each item
            // appearing/disappearing independently. Compact remains icon-only, while the
            // semantics below still expose the destination name to TalkBack.
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(itemWidth)
                    .height(48.dp)
                    .align(Alignment.CenterStart)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Tab.entries.forEach { tab ->
                    SlateNavigationItem(
                        tab = tab,
                        selected = selectedTab == tab,
                        expanded = true,
                        compact = true,
                        modifier = Modifier.weight(1f),
                        onClick = { if (selectedTab != tab) onSelect(tab) }
                    )
                }
            }
        }
    }
}

@Composable
fun SwiftSlateNavigationRail(
    selectedTab: Tab,
    expanded: Boolean,
    onSelect: (Tab) -> Unit
) {
    val motion = LocalSlateMotion.current
    val selectedIndex = Tab.entries.indexOf(selectedTab).coerceAtLeast(0)
    val railWidth = if (expanded) 228.dp else 88.dp
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(railWidth)
            .safeDrawingPadding()
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.20f)
        ),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = if (expanded) Alignment.Start else Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (expanded) 6.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
            ) {
                SlateMark(size = if (expanded) 40.dp else 44.dp)
                if (expanded) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "SWIFTSLATE",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.5.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(1.dp).padding(top = if (expanded) 28.dp else 20.dp))

            Box(modifier = Modifier.fillMaxWidth()) {
                val gap = 6.dp
                val itemHeight = 56.dp
                val indicatorOffset by animateDpAsState(
                    targetValue = (itemHeight + gap) * selectedIndex,
                    animationSpec = if (motion.reduceMotion) snap() else tween(220),
                    label = "rail_navigation_indicator"
                )

                // The rail uses the same shared indicator as the compact bar. The selected
                // destination therefore travels vertically through the same ordered tabs,
                // rather than the old background switching independently per item.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .offset(y = indicatorOffset)
                        .height(itemHeight)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(gap)
                ) {
                    Tab.entries.forEach { tab ->
                        SlateNavigationItem(
                            tab = tab,
                            selected = selectedTab == tab,
                            expanded = expanded,
                            compact = false,
                            sharedIndicator = true,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { if (selectedTab != tab) onSelect(tab) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SlateNavigationItem(
    tab: Tab,
    selected: Boolean,
    expanded: Boolean,
    compact: Boolean,
    sharedIndicator: Boolean = false,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val motion = LocalSlateMotion.current
    val label = stringResource(tab.titleRes)
    val background = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0f)
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .heightIn(min = 56.dp)
            .bounceClick(onClick)
            .semantics {
                this.selected = selected
                this.role = Role.Tab
                if (compact) this.contentDescription = label
            }
            .animateContentSize(animationSpec = motion.sizeTransitionSpec(180)),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0f),
        shape = RoundedCornerShape(18.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = if (compact) {
                    Modifier
                        .size(48.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(
                            if (compact) MaterialTheme.colorScheme.surface.copy(alpha = 0f) else background
                        )
                        .padding(12.dp)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            if (sharedIndicator) MaterialTheme.colorScheme.surface.copy(alpha = 0f) else background
                        )
                        .padding(
                            horizontal = if (expanded) 12.dp else 8.dp,
                            vertical = 8.dp
                        )
                },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = if (compact || expanded) null else label,
                    tint = contentColor,
                    modifier = Modifier.size(if (compact) 24.dp else 23.dp)
                )
                if (expanded) {
                    Text(
                        text = label,
                        color = contentColor,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }
    }
}
