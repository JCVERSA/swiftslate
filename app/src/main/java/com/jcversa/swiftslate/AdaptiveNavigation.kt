package com.jcversa.swiftslate

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
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

@Composable
fun SwiftSlateNavigationRail(
    selectedTab: Tab,
    expanded: Boolean,
    onSelect: (Tab) -> Unit
) {
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

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Tab.entries.forEach { tab ->
                    SlateNavigationItem(
                        tab = tab,
                        selected = selectedTab == tab,
                        expanded = expanded,
                        compact = false,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { if (selectedTab != tab) onSelect(tab) }
                    )
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
            .heightIn(min = if (compact) 52.dp else 56.dp)
            .clip(RoundedCornerShape(18.dp))
            .bounceClick(onClick)
            .background(background)
            .semantics {
                this.selected = selected
                this.role = Role.Tab
            }
            .animateContentSize(animationSpec = motion.sizeTransitionSpec(180)),
        color = background,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = if (expanded || compact) 12.dp else 8.dp,
                    vertical = if (compact) 5.dp else 8.dp
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = if (expanded || compact) null else label,
                tint = contentColor,
                modifier = Modifier.size(if (compact) 21.dp else 23.dp)
            )
            if (compact) {
                Text(
                    text = label,
                    color = contentColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 4.dp)
                )
            } else if (expanded) {
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
