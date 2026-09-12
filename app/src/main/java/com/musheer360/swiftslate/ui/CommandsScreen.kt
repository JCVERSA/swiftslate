package com.musheer360.swiftslate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musheer360.swiftslate.R
import com.musheer360.swiftslate.manager.CommandManager
import com.musheer360.swiftslate.model.Command
import com.musheer360.swiftslate.model.CommandType
import com.musheer360.swiftslate.ui.components.LocalSlateRhythm
import com.musheer360.swiftslate.ui.components.SlateCard
import com.musheer360.swiftslate.ui.components.SlateItemCard
import com.musheer360.swiftslate.ui.components.SlateTextField
import com.musheer360.swiftslate.ui.components.AnimateEntrance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen(commandManager: CommandManager) {
    val haptic = LocalHapticFeedback.current
    var commands by remember { mutableStateOf(commandManager.getCommands()) }
    val displayCommands = remember(commands) {
        val (builtIn, custom) = commands.partition { it.isBuiltIn }
        val (translateCmd, otherBuiltIns) = builtIn.partition { it.trigger.endsWith("translate:xx") }
        otherBuiltIns + translateCmd + custom
    }
    var trigger by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedType by rememberSaveable { mutableStateOf(CommandType.AI) }
    var editingTrigger by rememberSaveable { mutableStateOf<String?>(null) }
    var commandToDelete by remember { mutableStateOf<String?>(null) }
    var isFormExpanded by rememberSaveable { mutableStateOf(false) }
    val prefix = commandManager.getTriggerPrefix()
    val errorPrefixMsg = stringResource(R.string.commands_error_prefix, prefix)
    val errorDuplicateMsg = stringResource(R.string.commands_error_duplicate)
    val errorConflictTemplate = stringResource(R.string.commands_error_conflict, "\u0000")
    val errorEmptyTrigger = stringResource(R.string.commands_error_empty_trigger)
    val collapseLabel = stringResource(R.string.commands_collapse)
    val expandLabel = stringResource(R.string.commands_expand)

    // Search, filter, and collapse state
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilterTab by rememberSaveable { mutableStateOf(0) } // 0 = All, 1 = AI, 2 = Replace
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }

    val filteredCommands = remember(displayCommands, searchQuery, selectedFilterTab) {
        val baseList = displayCommands.filter {
            if (searchQuery.isBlank()) true
            else it.trigger.contains(searchQuery, ignoreCase = true)
        }
        when (selectedFilterTab) {
            1 -> baseList.filter { it.type == CommandType.AI }
            2 -> baseList.filter { it.type == CommandType.TEXT_REPLACER }
            else -> baseList
        }
    }

    val chevronRotation by animateFloatAsState(
        targetValue = if (isFormExpanded) 0f else 180f,
        animationSpec = tween(250),
        label = "chevron"
    )

    val rhythm = LocalSlateRhythm.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = rhythm.screenPaddingH, vertical = rhythm.screenPaddingV)
    ) {
        // Redesigned Top Header Row
        AnimateEntrance(index = 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = rhythm.cardGap),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = stringResource(R.string.commands_title),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = stringResource(R.string.commands_subtitle),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Elegant Search bar
        if (displayCommands.isNotEmpty()) {
            AnimateEntrance(index = 1) {
                val searchLabel = stringResource(R.string.commands_search_hint)
            val searchShape = RoundedCornerShape(16.dp)
            val searchBorderGradient = Brush.horizontalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                )
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .border(1.dp, searchBorderGradient, searchShape)
                    .semantics { contentDescription = searchLabel },
                shape = searchShape,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.SansSerif
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box {
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = searchLabel,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    if (searchQuery.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.commands_search_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { searchQuery = "" }
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Icon(
                        imageVector = if (expandedIds.isEmpty()) Icons.Rounded.FormatListBulleted else Icons.Rounded.UnfoldLess,
                        contentDescription = if (expandedIds.isEmpty()) expandLabel else collapseLabel,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                expandedIds = if (expandedIds.isEmpty()) {
                                    filteredCommands.map { it.trigger }.toSet()
                                } else {
                                    emptySet()
                                }
                            }
                    )
                }
            }
        }

            // Quick Category Filter Tabs (Pills style)
            AnimateEntrance(index = 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val filters = listOf(
                        stringResource(R.string.commands_filter_all),
                        stringResource(R.string.commands_filter_ai),
                        stringResource(R.string.commands_filter_replacer)
                    )
                    filters.forEachIndexed { index, label ->
                        val isSelected = selectedFilterTab == index
                        val bg = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
                        val fg = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        val border = if (isSelected) Color.Transparent else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)

                        Surface(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedFilterTab = index
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = bg,
                            border = if (!isSelected) androidx.compose.foundation.BorderStroke(1.dp, border) else null,
                            modifier = Modifier.height(34.dp)
                        ) {
                            Box(
                                modifier = Modifier.padding(horizontal = 14.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = fg
                                )
                            }
                        }
                    }
                }
            }

            // Commands list container card
            AnimateEntrance(index = 3) {
                SlateCard(modifier = Modifier.weight(1f)) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 4.dp)
                    ) {
                        if (filteredCommands.isEmpty()) {
                            item {
                                Text(
                                    text = if (searchQuery.isNotBlank()) stringResource(R.string.commands_search_empty) else stringResource(R.string.commands_empty_category),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        items(filteredCommands, key = { it.trigger }) { cmd ->
                            val isExpanded = cmd.trigger in expandedIds
                            SlateItemCard(
                                modifier = Modifier.clickable(
                                    interactionSource = null,
                                    indication = null,
                                    onClickLabel = if (isExpanded) collapseLabel else expandLabel
                                ) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    expandedIds = if (isExpanded) expandedIds - cmd.trigger
                                    else expandedIds + cmd.trigger
                                }
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = cmd.trigger,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 15.sp,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .background(
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            // Beautiful tag for command type
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (cmd.type == CommandType.AI)
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                                else
                                                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                                            ) {
                                                Text(
                                                    text = if (cmd.type == CommandType.AI) stringResource(R.string.commands_tag_ai) else stringResource(R.string.commands_tag_replacer),
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = if (cmd.type == CommandType.AI)
                                                        MaterialTheme.colorScheme.primary
                                                    else
                                                        MaterialTheme.colorScheme.tertiary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                )
                                            }
                                        }

                                        // Actions panel
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (!cmd.isBuiltIn) {
                                                IconButton(
                                                    onClick = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        trigger = cmd.trigger
                                                        prompt = cmd.prompt
                                                        selectedType = cmd.type
                                                        editingTrigger = cmd.trigger
                                                        errorMessage = null
                                                        isFormExpanded = true
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Edit,
                                                        contentDescription = stringResource(R.string.commands_edit_command),
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                                IconButton(
                                                    onClick = {
                                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                        commandToDelete = cmd.trigger
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Delete,
                                                        contentDescription = stringResource(R.string.commands_delete_command),
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            } else {
                                                Surface(
                                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                                    shape = RoundedCornerShape(6.dp)
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.commands_built_in).uppercase(),
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    AnimatedVisibility(
                                        visible = isExpanded,
                                        enter = expandVertically(
                                            animationSpec = tween(250),
                                            expandFrom = Alignment.Top
                                        ) + fadeIn(tween(200)),
                                        exit = shrinkVertically(
                                            animationSpec = tween(250),
                                            shrinkTowards = Alignment.Top
                                        ) + fadeOut(tween(150))
                                    ) {
                                        Column {
                                            Spacer(modifier = Modifier.height(10.dp))
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                                shape = RoundedCornerShape(10.dp),
                                                border = androidx.compose.foundation.BorderStroke(
                                                    1.dp,
                                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                                                )
                                            ) {
                                                Column(modifier = Modifier.padding(12.dp)) {
                                                    Text(
                                                        text = if (cmd.type == CommandType.AI) stringResource(R.string.commands_prompt_heading_ai) else stringResource(R.string.commands_prompt_heading_replacer),
                                                        fontSize = 8.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        letterSpacing = 1.sp
                                                    )
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Text(
                                                        text = cmd.prompt,
                                                        fontSize = 12.sp,
                                                        fontFamily = if (cmd.type == CommandType.TEXT_REPLACER) FontFamily.Monospace else FontFamily.SansSerif,
                                                        lineHeight = 16.sp,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(rhythm.cardGap))

        // Collapsible form card — styled as a pristine expandable bottom dock
        SlateCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = null,
                        indication = null,
                        onClickLabel = if (isFormExpanded) collapseLabel else expandLabel
                    ) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        isFormExpanded = !isFormExpanded
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (editingTrigger != null) Icons.Rounded.EditNote else Icons.Rounded.AddBox,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (editingTrigger != null) stringResource(R.string.commands_edit_custom_title) else stringResource(R.string.commands_add_custom_title),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { rotationZ = chevronRotation }
                )
            }

            AnimatedVisibility(
                visible = isFormExpanded,
                enter = expandVertically(
                    animationSpec = tween(250),
                    expandFrom = Alignment.Top
                ) + fadeIn(tween(200)),
                exit = shrinkVertically(
                    animationSpec = tween(250),
                    shrinkTowards = Alignment.Top
                ) + fadeOut(tween(150))
            ) {
                Column {
                    Spacer(modifier = Modifier.height(rhythm.groupGap))
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SegmentedButton(
                            selected = selectedType == CommandType.AI,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedType = CommandType.AI
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                activeBorderColor = MaterialTheme.colorScheme.primary,
                                inactiveContainerColor = MaterialTheme.colorScheme.surface,
                                inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                inactiveBorderColor = MaterialTheme.colorScheme.outline
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.commands_type_ai), fontSize = 11.sp)
                            }
                        }
                        SegmentedButton(
                            selected = selectedType == CommandType.TEXT_REPLACER,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedType = CommandType.TEXT_REPLACER
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = MaterialTheme.colorScheme.primary,
                                activeContentColor = MaterialTheme.colorScheme.onPrimary,
                                activeBorderColor = MaterialTheme.colorScheme.primary,
                                inactiveContainerColor = MaterialTheme.colorScheme.surface,
                                inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                inactiveBorderColor = MaterialTheme.colorScheme.outline
                            )
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.FindReplace, null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.commands_type_replacer), fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(rhythm.groupGap))
                    SlateTextField(
                        value = trigger,
                        onValueChange = {
                            trigger = it.take(CommandManager.MAX_TRIGGER_LENGTH)
                            errorMessage = null
                        },
                        label = { Text(stringResource(R.string.commands_trigger_label, prefix)) },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(rhythm.formGap))
                    SlateTextField(
                        value = prompt,
                        onValueChange = {
                            prompt = it.take(CommandManager.MAX_PROMPT_LENGTH)
                            errorMessage = null
                        },
                        label = { Text(if (selectedType == CommandType.AI) stringResource(R.string.commands_prompt_label) else stringResource(R.string.commands_replacement_label)) },
                        singleLine = false,
                        modifier = Modifier.height(100.dp)
                    )
                    errorMessage?.let { msg ->
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = rhythm.bodySize,
                            modifier = Modifier.padding(top = rhythm.formGap)
                        )
                    }
                    Spacer(modifier = Modifier.height(rhythm.groupGap))
                    if (editingTrigger != null) {
                        TextButton(
                            onClick = {
                                trigger = ""
                                prompt = ""
                                errorMessage = null
                                editingTrigger = null
                                selectedType = CommandType.AI
                                isFormExpanded = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.commands_cancel))
                        }
                    }
                    Button(
                        onClick = {
                            val trimmedTrigger = trigger.trim()
                            if (trimmedTrigger.isNotBlank() && prompt.isNotBlank()) {
                                if (!trimmedTrigger.startsWith(prefix)) {
                                    errorMessage = errorPrefixMsg
                                    return@Button
                                }
                                if (trimmedTrigger == prefix || trimmedTrigger.length <= prefix.length) {
                                    errorMessage = errorEmptyTrigger
                                    return@Button
                                }
                                if (commands.any { it.trigger == trimmedTrigger && it.trigger != editingTrigger }) {
                                    errorMessage = errorDuplicateMsg
                                    return@Button
                                }
                                val conflicting = commands.firstOrNull {
                                    it.trigger != editingTrigger &&
                                    (it.trigger.startsWith(trimmedTrigger) || trimmedTrigger.startsWith(it.trigger))
                                }
                                if (conflicting != null) {
                                    errorMessage = errorConflictTemplate.replace("\u0000", conflicting.trigger)
                                    return@Button
                                }
                                if (!CommandManager.isValidCommand(trimmedTrigger, prompt.trim(), prefix)) {
                                    errorMessage = errorEmptyTrigger
                                    return@Button
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val saved = commandManager.saveCustomCommand(
                                    command = Command(trimmedTrigger, prompt.trim(), false, selectedType),
                                    replacing = editingTrigger ?: trimmedTrigger
                                )
                                commands = commandManager.getCommands()
                                if (!saved) return@Button
                                trigger = ""
                                prompt = ""
                                errorMessage = null
                                editingTrigger = null
                                selectedType = CommandType.AI
                                isFormExpanded = false
                            }
                        },
                        enabled = trigger.isNotBlank() && trigger.trim() != prefix && prompt.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                    ) {
                        Text(
                            text = if (editingTrigger != null) stringResource(R.string.commands_save_command) else stringResource(R.string.commands_add_command),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    commandToDelete?.let { triggerToDelete ->
        AlertDialog(
            onDismissRequest = { commandToDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_command_title)) },
            text = { Text(stringResource(R.string.delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    commandManager.removeCustomCommand(triggerToDelete)
                    expandedIds = expandedIds - triggerToDelete
                    if (editingTrigger == triggerToDelete) {
                        trigger = ""
                        prompt = ""
                        errorMessage = null
                        editingTrigger = null
                        selectedType = CommandType.AI
                        isFormExpanded = false
                    }
                    commands = commandManager.getCommands()
                    commandToDelete = null
                }) {
                    Text(stringResource(R.string.delete_confirm_button), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { commandToDelete = null }) {
                    Text(stringResource(R.string.commands_cancel))
                }
            }
        )
    }
}
