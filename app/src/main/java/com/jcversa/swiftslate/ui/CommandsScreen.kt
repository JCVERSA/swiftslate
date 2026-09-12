package com.jcversa.swiftslate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcversa.swiftslate.R
import com.jcversa.swiftslate.SwiftSlateApp
import com.jcversa.swiftslate.api.GeminiClient
import com.jcversa.swiftslate.api.OpenAICompatibleClient
import com.jcversa.swiftslate.manager.CommandManager
import com.jcversa.swiftslate.model.Command
import com.jcversa.swiftslate.model.CommandType
import com.jcversa.swiftslate.service.CommandOutcome
import com.jcversa.swiftslate.service.runTextCommand
import com.jcversa.swiftslate.ui.components.LocalSlateRhythm
import com.jcversa.swiftslate.ui.components.SlateCard
import com.jcversa.swiftslate.ui.components.SlateItemCard
import com.jcversa.swiftslate.ui.components.SlateTextField
import com.jcversa.swiftslate.ui.components.AnimateEntrance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen(commandManager: CommandManager) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val geminiClient = remember { GeminiClient() }
    val openAIClient = remember { OpenAICompatibleClient() }
    var commands by remember { mutableStateOf(commandManager.getCommands()) }
    val displayCommands = remember(commands) {
        val (builtIn, custom) = commands.partition { it.isBuiltIn }
        val (translateCmd, otherBuiltIns) = builtIn.partition { it.trigger.endsWith("translate:xx") }
        otherBuiltIns + translateCmd + custom
    }
    var trigger by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var aliasInput by remember { mutableStateOf("") }
    var aliases by remember { mutableStateOf<List<String>>(emptyList()) }
    var editingAlias by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedType by rememberSaveable { mutableStateOf(CommandType.AI) }
    var editingTrigger by rememberSaveable { mutableStateOf<String?>(null) }
    var commandToDelete by remember { mutableStateOf<String?>(null) }
    var isFormExpanded by rememberSaveable { mutableStateOf(false) }
    var previewInput by rememberSaveable { mutableStateOf("") }
    var previewOutput by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var isPreviewing by remember { mutableStateOf(false) }
    val prefix = commandManager.getTriggerPrefix()
    val errorPrefixMsg = stringResource(R.string.commands_error_prefix, prefix)
    val errorDuplicateMsg = stringResource(R.string.commands_error_duplicate)
    val errorConflictTemplate = stringResource(R.string.commands_error_conflict, "\u0000")
    val errorEmptyTrigger = stringResource(R.string.commands_error_empty_trigger)
    val aliasPrefixError = stringResource(R.string.commands_alias_error_prefix, prefix)
    val aliasDuplicateError = stringResource(R.string.commands_alias_error_duplicate)
    val aliasLimitError = stringResource(R.string.commands_alias_error_limit)
    val aliasInvalidError = stringResource(R.string.commands_alias_error_invalid)
    val collapseLabel = stringResource(R.string.commands_collapse)
    val expandLabel = stringResource(R.string.commands_expand)
    val previewDefaultInput = stringResource(R.string.commands_preview_default_input)
    val previewUnavailable = stringResource(R.string.commands_preview_unavailable)
    val previewTimeout = stringResource(R.string.commands_preview_timeout)

    fun addAlias() {
        val alias = aliasInput.trim()
        val currentTrigger = trigger.trim()
        val updatedAliases = if (editingAlias != null) {
            aliases.map { if (it == editingAlias) alias else it }
        } else {
            aliases + alias
        }
        val aliasNamesConflict: (String, String) -> Boolean = { first, second ->
            first == second || first.startsWith(second) || second.startsWith(first)
        }
        val aliasesConflict = updatedAliases.indices.any { index ->
            ((index + 1) until updatedAliases.size).any { other ->
                aliasNamesConflict(updatedAliases[index], updatedAliases[other])
            }
        }
        val otherCommands = commands.filter { it.trigger != editingTrigger }
        when {
            alias.isBlank() -> return
            !alias.startsWith(prefix) || alias.length <= prefix.length -> errorMessage = aliasPrefixError
            alias.length > CommandManager.MAX_ALIAS_LENGTH -> errorMessage = aliasInvalidError
            updatedAliases.size > CommandManager.MAX_ALIASES -> errorMessage = aliasLimitError
            alias == currentTrigger || aliasesConflict -> errorMessage = aliasDuplicateError
            currentTrigger.isNotBlank() &&
                !CommandManager.areValidAliases(updatedAliases, currentTrigger, prefix) ->
                errorMessage = aliasInvalidError
            otherCommands.any { command ->
                val names = listOf(command.trigger) + command.aliases
                updatedAliases.any { candidate ->
                    names.any { existing -> aliasNamesConflict(candidate, existing) }
                }
            } -> errorMessage = aliasDuplicateError
            else -> {
                aliases = updatedAliases
                aliasInput = ""
                editingAlias = null
                errorMessage = null
            }
        }
    }

    fun previewCommand() {
        val sample = previewInput.trim().ifBlank { previewDefaultInput }
        val commandPrompt = prompt.trim()
        if (commandPrompt.isBlank() || isPreviewing) return
        previewOutput = null
        previewError = null
        isPreviewing = true
        scope.launch {
            val outcome = if (selectedType == CommandType.TEXT_REPLACER) {
                CommandOutcome.Success(commandPrompt)
            } else {
                val app = context.applicationContext as? SwiftSlateApp
                if (app == null) {
                    CommandOutcome.Unavailable(previewUnavailable)
                } else {
                    try {
                        withContext(Dispatchers.IO) {
                            withTimeout(90_000L) {
                                runTextCommand(
                                    context.applicationContext,
                                    app.keyManager,
                                    geminiClient,
                                    openAIClient,
                                    commandPrompt,
                                    sample
                                )
                            }
                        }
                    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                        CommandOutcome.Failure(previewTimeout)
                    } catch (_: Exception) {
                        CommandOutcome.Failure(previewUnavailable)
                    }
                }
            }
            isPreviewing = false
            when (outcome) {
                is CommandOutcome.Success -> previewOutput = outcome.text
                is CommandOutcome.Refusal -> previewError = previewUnavailable
                is CommandOutcome.Unavailable -> previewError = outcome.message
                is CommandOutcome.Failure -> previewError = outcome.message
            }
        }
    }

    // Search, filter, and collapse state
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilterTab by rememberSaveable { mutableStateOf(0) } // 0 = All, 1 = AI, 2 = Replace
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }

    val filteredCommands = remember(displayCommands, searchQuery, selectedFilterTab) {
        val baseList = displayCommands.filter {
            if (searchQuery.isBlank()) true
            else it.trigger.contains(searchQuery, ignoreCase = true) ||
                it.aliases.any { alias -> alias.contains(searchQuery, ignoreCase = true) }
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
                        style = MaterialTheme.typography.headlineMedium,
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
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.commands_search_close),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(2.dp))
                    }
                    IconButton(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            expandedIds = if (expandedIds.isEmpty()) {
                                filteredCommands.map { it.trigger }.toSet()
                            } else {
                                emptySet()
                            }
                        },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = if (expandedIds.isEmpty()) Icons.Rounded.FormatListBulleted else Icons.Rounded.UnfoldLess,
                            contentDescription = if (expandedIds.isEmpty()) expandLabel else collapseLabel,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
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
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .semantics { selected = isSelected }
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
                                                        aliases = cmd.aliases
                                                        aliasInput = ""
                                                        editingAlias = null
                                                        selectedType = cmd.type
                                                        editingTrigger = cmd.trigger
                                                        errorMessage = null
                                                        previewInput = ""
                                                        previewOutput = null
                                                        previewError = null
                                                        isFormExpanded = true
                                                    },
                                                    modifier = Modifier.size(48.dp)
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
                                                    modifier = Modifier.size(48.dp)
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
                                            if (cmd.aliases.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(8.dp))
                                                Text(
                                                    text = stringResource(R.string.commands_aliases_heading),
                                                    fontSize = 8.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    letterSpacing = 1.sp
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = cmd.aliases.joinToString("  "),
                                                    fontSize = 12.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SlateTextField(
                            value = aliasInput,
                            onValueChange = {
                                aliasInput = it.take(CommandManager.MAX_ALIAS_LENGTH)
                                errorMessage = null
                            },
                            label = { Text(stringResource(R.string.commands_alias_label)) },
                            placeholder = { Text(stringResource(R.string.commands_alias_placeholder, prefix)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { addAlias() },
                            enabled = aliasInput.isNotBlank(),
                            modifier = Modifier.padding(start = 4.dp)
                        ) {
                            Icon(
                                imageVector = if (editingAlias != null) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircle,
                                contentDescription = stringResource(
                                    if (editingAlias != null) R.string.commands_alias_update
                                    else R.string.commands_alias_add
                                ),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (aliases.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            aliases.forEach { alias ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = alias,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.padding(start = 8.dp, top = 5.dp, bottom = 5.dp)
                                        )
                                        IconButton(
                                            onClick = {
                                                aliasInput = alias
                                                editingAlias = alias
                                                errorMessage = null
                                            },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Edit,
                                                contentDescription = stringResource(R.string.commands_alias_edit),
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                aliases = aliases - alias
                                                if (editingAlias == alias) {
                                                    editingAlias = null
                                                    aliasInput = ""
                                                }
                                            },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Close,
                                                contentDescription = stringResource(R.string.commands_alias_remove),
                                                modifier = Modifier.size(14.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(rhythm.formGap))
                    SlateTextField(
                        value = prompt,
                        onValueChange = {
                            prompt = it.take(CommandManager.MAX_PROMPT_LENGTH)
                            errorMessage = null
                            previewOutput = null
                            previewError = null
                        },
                        label = { Text(if (selectedType == CommandType.AI) stringResource(R.string.commands_prompt_label) else stringResource(R.string.commands_replacement_label)) },
                        singleLine = false,
                        modifier = Modifier.height(100.dp)
                    )
                    Spacer(modifier = Modifier.height(rhythm.formGap))
                    SlateTextField(
                        value = previewInput,
                        onValueChange = { previewInput = it.take(10_000) },
                        label = { Text(stringResource(R.string.commands_preview_input_label)) },
                        placeholder = { Text(previewDefaultInput) },
                        singleLine = false,
                        modifier = Modifier.height(84.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = rhythm.formGap),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isPreviewing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        TextButton(
                            onClick = { previewCommand() },
                            enabled = prompt.isNotBlank() && !isPreviewing
                        ) {
                            Text(if (isPreviewing) stringResource(R.string.commands_preview_loading) else stringResource(R.string.commands_preview_button))
                        }
                    }
                    previewOutput?.let { output ->
                        SlateCard(modifier = Modifier.padding(top = rhythm.formGap)) {
                            Column {
                                Text(
                                    text = stringResource(R.string.commands_preview_output),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(text = output, color = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                    previewError?.let { msg ->
                        Text(
                            text = msg,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = rhythm.bodySize,
                            modifier = Modifier.padding(top = rhythm.formGap)
                        )
                    }
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
                                aliasInput = ""
                                aliases = emptyList()
                                errorMessage = null
                                previewOutput = null
                                previewError = null
                                previewInput = ""
                                editingTrigger = null
                                editingAlias = null
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
                                val newTriggers = listOf(trimmedTrigger) + aliases
                                val conflictingAlias = commands.firstOrNull { command ->
                                    if (command.trigger == editingTrigger) return@firstOrNull false
                                    val existingTriggers = listOf(command.trigger) + command.aliases
                                    newTriggers.any { candidate ->
                                        existingTriggers.any { existing ->
                                            candidate == existing || candidate.startsWith(existing) || existing.startsWith(candidate)
                                        }
                                    }
                                }
                                if (conflictingAlias != null) {
                                    errorMessage = errorDuplicateMsg
                                    return@Button
                                }
                                if (!CommandManager.isValidCommand(trimmedTrigger, prompt.trim(), prefix) ||
                                    !CommandManager.areValidAliases(aliases, trimmedTrigger, prefix)
                                ) {
                                    errorMessage = aliasInvalidError
                                    return@Button
                                }
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                val saved = commandManager.saveCustomCommand(
                                    command = Command(trimmedTrigger, prompt.trim(), false, selectedType, aliases),
                                    replacing = editingTrigger ?: trimmedTrigger
                                )
                                if (!saved) {
                                    // Keep the form intact and explain a rejection from the
                                    // manager as well. This covers a concurrent edit or a
                                    // collision introduced outside this screen, which the local
                                    // preflight checks cannot observe.
                                    errorMessage = errorDuplicateMsg
                                    return@Button
                                }
                                commands = commandManager.getCommands()
                                trigger = ""
                                prompt = ""
                                aliasInput = ""
                                aliases = emptyList()
                                errorMessage = null
                                previewOutput = null
                                previewError = null
                                previewInput = ""
                                editingTrigger = null
                                editingAlias = null
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
                        editingAlias = null
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
