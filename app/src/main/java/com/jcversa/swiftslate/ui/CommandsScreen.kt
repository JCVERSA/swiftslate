package com.jcversa.swiftslate.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jcversa.swiftslate.R
import com.jcversa.swiftslate.SwiftSlateApp
import com.jcversa.swiftslate.api.GeminiClient
import com.jcversa.swiftslate.api.OpenAICompatibleClient
import com.jcversa.swiftslate.manager.CommandManager
import com.jcversa.swiftslate.manager.HistoryManager
import com.jcversa.swiftslate.model.Command
import com.jcversa.swiftslate.model.CommandType
import com.jcversa.swiftslate.model.HistoryEntry
import com.jcversa.swiftslate.service.CommandOutcome
import com.jcversa.swiftslate.service.runTextCommand
import com.jcversa.swiftslate.ui.components.AnimateEntrance
import com.jcversa.swiftslate.ui.components.LocalSlateRhythm
import com.jcversa.swiftslate.ui.components.SlateCard
import com.jcversa.swiftslate.ui.components.SlateItemCard
import com.jcversa.swiftslate.ui.components.SlateMorphIcon
import com.jcversa.swiftslate.ui.components.SlateMorphIconType
import com.jcversa.swiftslate.ui.components.SlateTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommandsScreen(commandManager: CommandManager) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val geminiClient = remember { GeminiClient() }
    val openAIClient = remember { OpenAICompatibleClient() }
    val historyManager = remember { HistoryManager(context) }
    val rhythm = LocalSlateRhythm.current

    var commands by remember { mutableStateOf(commandManager.getCommands()) }
    val displayCommands = remember(commands) {
        val (builtIn, custom) = commands.partition { it.isBuiltIn }
        val (translate, otherBuiltIns) = builtIn.partition { it.trigger.endsWith("translate:xx") }
        otherBuiltIns + translate + custom
    }

    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableIntStateOf(0) }
    var expandedIds by remember { mutableStateOf(emptySet<String>()) }

    var showEditor by rememberSaveable { mutableStateOf(false) }
    var editingTrigger by rememberSaveable { mutableStateOf<String?>(null) }
    var trigger by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var selectedType by rememberSaveable { mutableStateOf(CommandType.AI) }
    var aliases by remember { mutableStateOf<List<String>>(emptyList()) }
    var aliasInput by remember { mutableStateOf("") }
    var editingAlias by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var commandToDelete by remember { mutableStateOf<String?>(null) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var historyEntries by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }

    var previewInput by rememberSaveable { mutableStateOf("") }
    var previewOutput by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var isPreviewing by remember { mutableStateOf(false) }
    var isSavingCommand by remember { mutableStateOf(false) }

    fun openHistory() {
        showHistory = true
        scope.launch {
            historyEntries = withContext(Dispatchers.IO) { historyManager.getEntries() }
        }
    }

    val prefix = commandManager.getTriggerPrefix()
    val errorPrefix = stringResource(R.string.commands_error_prefix, prefix)
    val errorDuplicate = stringResource(R.string.commands_error_duplicate)
    val errorConflict = stringResource(R.string.commands_error_conflict, "\u0000")
    val errorEmpty = stringResource(R.string.commands_error_empty_trigger)
    val aliasPrefixError = stringResource(R.string.commands_alias_error_prefix, prefix)
    val aliasDuplicateError = stringResource(R.string.commands_alias_error_duplicate)
    val aliasLimitError = stringResource(R.string.commands_alias_error_limit)
    val aliasInvalidError = stringResource(R.string.commands_alias_error_invalid)
    val searchLabel = stringResource(R.string.commands_search_hint)
    val expandLabel = stringResource(R.string.commands_expand)
    val collapseLabel = stringResource(R.string.commands_collapse)
    val previewUnavailable = stringResource(R.string.commands_preview_unavailable)
    val previewTimeout = stringResource(R.string.commands_preview_timeout)
    val previewDefaultInput = stringResource(R.string.commands_preview_default_input)

    val filteredCommands = remember(displayCommands, searchQuery, selectedFilter) {
        val searched = displayCommands.filter { command ->
            searchQuery.isBlank() ||
                command.trigger.contains(searchQuery, ignoreCase = true) ||
                command.aliases.any { it.contains(searchQuery, ignoreCase = true) }
        }
        when (selectedFilter) {
            1 -> searched.filter { it.type == CommandType.AI }
            2 -> searched.filter { it.type == CommandType.TEXT_REPLACER }
            else -> searched
        }
    }

    fun resetEditor() {
        editingTrigger = null
        trigger = ""
        prompt = ""
        selectedType = CommandType.AI
        aliases = emptyList()
        aliasInput = ""
        editingAlias = null
        errorMessage = null
        previewInput = ""
        previewOutput = null
        previewError = null
    }

    fun closeEditor() {
        resetEditor()
        showEditor = false
    }

    fun startNewCommand() {
        resetEditor()
        showEditor = true
    }

    fun startEditing(command: Command) {
        editingTrigger = command.trigger
        trigger = command.trigger
        prompt = command.prompt
        selectedType = command.type
        aliases = command.aliases
        aliasInput = ""
        editingAlias = null
        errorMessage = null
        previewInput = ""
        previewOutput = null
        previewError = null
        showEditor = true
    }

    fun namesConflict(first: String, second: String): Boolean =
        first == second || first.startsWith(second) || second.startsWith(first)

    fun validateAliases(candidateAliases: List<String>, currentTrigger: String): String? {
        val alias = aliasInput.trim()
        when {
            alias.isBlank() -> return null
            !alias.startsWith(prefix) || alias.length <= prefix.length -> return aliasPrefixError
            alias.length > CommandManager.MAX_ALIAS_LENGTH -> return aliasInvalidError
            candidateAliases.size > CommandManager.MAX_ALIASES -> return aliasLimitError
            alias == currentTrigger -> return aliasDuplicateError
            candidateAliases.indices.any { index ->
                ((index + 1) until candidateAliases.size).any { other ->
                    namesConflict(candidateAliases[index], candidateAliases[other])
                }
            } -> return aliasDuplicateError
        }

        if (currentTrigger.isNotBlank() &&
            !CommandManager.areValidAliases(candidateAliases, currentTrigger, prefix)
        ) return aliasInvalidError

        val otherCommands = commands.filter { it.trigger != editingTrigger }
        if (candidateAliases.any { candidate ->
                otherCommands.any { command ->
                    (listOf(command.trigger) + command.aliases).any { existing ->
                        namesConflict(candidate, existing)
                    }
                }
            }
        ) return aliasDuplicateError
        return null
    }

    fun saveAlias() {
        val candidate = aliasInput.trim()
        val candidateAliases = if (editingAlias == null) {
            aliases + candidate
        } else {
            aliases.map { if (it == editingAlias) candidate else it }
        }
        val validation = validateAliases(candidateAliases, trigger.trim())
        if (candidate.isBlank()) return
        if (validation != null) {
            errorMessage = validation
            return
        }
        aliases = candidateAliases
        aliasInput = ""
        editingAlias = null
        errorMessage = null
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

    fun saveCommand() {
        val trimmedTrigger = trigger.trim()
        val trimmedPrompt = prompt.trim()
        when {
            !trimmedTrigger.startsWith(prefix) -> {
                errorMessage = errorPrefix
                return
            }
            trimmedTrigger == prefix || trimmedTrigger.length <= prefix.length -> {
                errorMessage = errorEmpty
                return
            }
            trimmedPrompt.isBlank() -> return
            commands.any { it.trigger == trimmedTrigger && it.trigger != editingTrigger } -> {
                errorMessage = errorDuplicate
                return
            }
        }

        val candidateNames = listOf(trimmedTrigger) + aliases
        val conflict = commands
            .filter { it.trigger != editingTrigger }
            .firstOrNull { command ->
                val existingNames = listOf(command.trigger) + command.aliases
                candidateNames.any { candidate ->
                    existingNames.any { existing -> namesConflict(candidate, existing) }
                }
            }
        if (conflict != null) {
            errorMessage = errorConflict.replace("\u0000", conflict.trigger)
            return
        }

        if (!CommandManager.isValidCommand(trimmedTrigger, trimmedPrompt, prefix) ||
            !CommandManager.areValidAliases(aliases, trimmedTrigger, prefix)
        ) {
            errorMessage = aliasInvalidError
            return
        }

        if (isSavingCommand) return
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        isSavingCommand = true
        val commandToSave = Command(trimmedTrigger, trimmedPrompt, false, selectedType, aliases)
        val oldTrigger = editingTrigger ?: trimmedTrigger
        scope.launch {
            val savedCommands = withContext(Dispatchers.IO) {
                val saved = commandManager.saveCustomCommand(
                    command = commandToSave,
                    replacing = oldTrigger
                )
                if (saved) commandManager.getCommands() else null
            }
            isSavingCommand = false
            if (savedCommands == null) {
                errorMessage = errorDuplicate
                return@launch
            }
            commands = savedCommands
            expandedIds = expandedIds - oldTrigger
            closeEditor()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = rhythm.screenPaddingH, vertical = rhythm.screenPaddingV)
    ) {
        AnimateEntrance(index = 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = rhythm.cardGap),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = { openHistory() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = stringResource(R.string.commands_history_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Surface(
                        onClick = { startNewCommand() },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.commands_add_custom_title),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }

        if (displayCommands.isNotEmpty()) {
            AnimateEntrance(index = 1) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = rhythm.cardGap)
                        .semantics { contentDescription = searchLabel },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(start = 12.dp, end = 4.dp),
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
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = searchLabel,
                                            fontSize = 15.sp,
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
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                            SlateMorphIcon(
                                type = SlateMorphIconType.ExpandCollapse,
                                toggled = expandedIds.isNotEmpty(),
                                tint = MaterialTheme.colorScheme.primary,
                                contentDescription = if (expandedIds.isEmpty()) expandLabel else collapseLabel,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            AnimateEntrance(index = 2) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = rhythm.cardGap)
                ) {
                    val filters = listOf(
                        stringResource(R.string.commands_filter_all),
                        stringResource(R.string.commands_filter_ai),
                        stringResource(R.string.commands_filter_replacer)
                    )
                    filters.forEachIndexed { index, label ->
                        SegmentedButton(
                            selected = selectedFilter == index,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedFilter = index
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = filters.size),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        AnimateEntrance(index = 3) {
            SlateCard(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.small),
                    verticalArrangement = Arrangement.spacedBy(rhythm.listGap),
                    contentPadding = PaddingValues(bottom = 4.dp)
                ) {
                    if (filteredCommands.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp, horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.SearchOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = if (searchQuery.isBlank()) {
                                        stringResource(R.string.commands_empty_category)
                                    } else {
                                        stringResource(R.string.commands_search_empty)
                                    },
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                    items(filteredCommands, key = { it.trigger }) { command ->
                        val expanded = command.trigger in expandedIds
                        SlateItemCard(
                            modifier = Modifier.clickable(
                                onClickLabel = if (expanded) collapseLabel else expandLabel
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                expandedIds = if (expanded) expandedIds - command.trigger
                                else expandedIds + command.trigger
                            }
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = command.trigger,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .background(
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                                                        RoundedCornerShape(8.dp)
                                                    )
                                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = if (command.type == CommandType.AI) {
                                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                                } else {
                                                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f)
                                                }
                                            ) {
                                                Text(
                                                    text = if (command.type == CommandType.AI) {
                                                        stringResource(R.string.commands_tag_ai)
                                                    } else {
                                                        stringResource(R.string.commands_tag_replacer)
                                                    },
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (command.type == CommandType.AI) {
                                                        MaterialTheme.colorScheme.onPrimaryContainer
                                                    } else {
                                                        MaterialTheme.colorScheme.onTertiaryContainer
                                                    },
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                        if (command.aliases.isNotEmpty()) {
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = command.aliases.joinToString("  ·  "),
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    if (!command.isBuiltIn) {
                                        IconButton(
                                            onClick = { startEditing(command) },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Edit,
                                                contentDescription = stringResource(R.string.commands_edit_command),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                commandToDelete = command.trigger
                                            },
                                            modifier = Modifier.size(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.DeleteOutline,
                                                contentDescription = stringResource(R.string.commands_delete_command),
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    } else {
                                        Text(
                                            text = stringResource(R.string.commands_built_in),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 8.dp)
                                        )
                                    }
                                }
                                AnimatedVisibility(
                                    visible = expanded,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Column {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Surface(
                                            modifier = Modifier.fillMaxWidth(),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                                            shape = MaterialTheme.shapes.small
                                        ) {
                                            Column(modifier = Modifier.padding(12.dp)) {
                                                Text(
                                                    text = if (command.type == CommandType.AI) {
                                                        stringResource(R.string.commands_prompt_heading_ai)
                                                    } else {
                                                        stringResource(R.string.commands_prompt_heading_replacer)
                                                    },
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    letterSpacing = 1.sp
                                                )
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(
                                                    text = command.prompt,
                                                    fontSize = 12.sp,
                                                    lineHeight = 17.sp,
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
    }

    if (showEditor) {
        ModalBottomSheet(
            onDismissRequest = { closeEditor() },
            containerColor = MaterialTheme.colorScheme.background,
            tonalElevation = 4.dp,
            dragHandle = {
                BottomSheetDefaults.DragHandle(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                contentPadding = PaddingValues(
                    start = rhythm.screenPaddingH,
                    end = rhythm.screenPaddingH,
                    bottom = 32.dp
                ),
                verticalArrangement = Arrangement.spacedBy(rhythm.formGap)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (editingTrigger == null) {
                                    stringResource(R.string.commands_add_custom_title)
                                } else {
                                    stringResource(R.string.commands_edit_custom_title)
                                },
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = if (editingTrigger == null) {
                                    stringResource(R.string.commands_subtitle)
                                } else {
                                    editingTrigger.orEmpty()
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { closeEditor() }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = stringResource(R.string.commands_cancel)
                            )
                        }
                    }
                }
                item {
                    SlateTextField(
                        value = trigger,
                        onValueChange = {
                            trigger = it.take(CommandManager.MAX_TRIGGER_LENGTH)
                            errorMessage = null
                        },
                        label = { Text(stringResource(R.string.commands_trigger_label, prefix)) },
                        singleLine = true
                    )
                }
                item {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = selectedType == CommandType.AI,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedType = CommandType.AI
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.commands_type_ai)) }
                        SegmentedButton(
                            selected = selectedType == CommandType.TEXT_REPLACER,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                selectedType = CommandType.TEXT_REPLACER
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(R.string.commands_type_replacer)) }
                    }
                }
                item {
                    SlateTextField(
                        value = prompt,
                        onValueChange = {
                            prompt = it.take(CommandManager.MAX_PROMPT_LENGTH)
                            errorMessage = null
                        },
                        label = {
                            Text(
                                if (selectedType == CommandType.AI) {
                                    stringResource(R.string.commands_prompt_label)
                                } else {
                                    stringResource(R.string.commands_replacement_label)
                                }
                            )
                        },
                        singleLine = false,
                        modifier = Modifier.heightIn(min = 112.dp)
                    )
                }
                if (selectedType == CommandType.AI) {
                    item {
                        SlateTextField(
                            value = previewInput,
                            onValueChange = { previewInput = it },
                            label = { Text(stringResource(R.string.commands_preview_input_label)) },
                            singleLine = false,
                            modifier = Modifier.heightIn(min = 80.dp)
                        )
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isPreviewing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            TextButton(
                                onClick = { previewCommand() },
                                enabled = prompt.isNotBlank() && !isPreviewing
                            ) {
                                Text(
                                    if (isPreviewing) stringResource(R.string.commands_preview_loading)
                                    else stringResource(R.string.commands_preview_button)
                                )
                            }
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(R.string.commands_aliases_heading),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.commands_alias_label),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                                label = {
                                    Text(
                                        if (editingAlias == null) {
                                            stringResource(R.string.commands_alias_placeholder, prefix)
                                        } else {
                                            stringResource(R.string.commands_alias_edit)
                                        }
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                onClick = { saveAlias() },
                                enabled = aliasInput.isNotBlank(),
                                shape = CircleShape,
                                color = if (aliasInput.isNotBlank()) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                SlateMorphIcon(
                                    type = SlateMorphIconType.AddCheck,
                                    toggled = editingAlias != null,
                                    contentDescription = if (editingAlias == null) {
                                        stringResource(R.string.commands_alias_add)
                                    } else {
                                        stringResource(R.string.commands_alias_update)
                                    },
                                    tint = if (aliasInput.isNotBlank()) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                        if (editingAlias != null) {
                            TextButton(
                                onClick = {
                                    aliasInput = ""
                                    editingAlias = null
                                    errorMessage = null
                                },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Text(stringResource(R.string.commands_cancel))
                            }
                        }
                        aliases.forEach { alias ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 52.dp)
                                        .padding(start = 12.dp, end = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = alias,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.weight(1f)
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
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            aliases = aliases - alias
                                            if (editingAlias == alias) {
                                                aliasInput = ""
                                                editingAlias = null
                                            }
                                            errorMessage = null
                                        },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = stringResource(R.string.commands_alias_remove),
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    previewOutput?.let { output ->
                        SlateCard {
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
                    previewError?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp
                        )
                    }
                    errorMessage?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { closeEditor() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.commands_cancel))
                        }
                        Button(
                            onClick = { saveCommand() },
                            enabled = trigger.isNotBlank() && prompt.isNotBlank() && !isSavingCommand,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (editingTrigger == null) {
                                    stringResource(R.string.commands_add_command)
                                } else {
                                    stringResource(R.string.commands_save_command)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showHistory) {
        ModalBottomSheet(
            onDismissRequest = { showHistory = false },
            containerColor = MaterialTheme.colorScheme.background,
            dragHandle = {
                BottomSheetDefaults.DragHandle(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                )
            }
        ) {
            val dateFormatter = remember { SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()) }
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = rhythm.screenPaddingH),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.commands_history_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = if (historyManager.isEnabled) {
                                stringResource(R.string.commands_history_desc)
                            } else {
                                stringResource(R.string.commands_history_disabled)
                            },
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (historyEntries.isNotEmpty()) {
                        TextButton(onClick = {
                            scope.launch(Dispatchers.IO) {
                                historyManager.clear()
                            }
                            historyEntries = emptyList()
                        }) {
                            Text(
                                text = stringResource(R.string.commands_history_clear),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                if (historyEntries.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = rhythm.screenPaddingH, vertical = 44.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.commands_history_empty),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(
                            start = rhythm.screenPaddingH,
                            end = rhythm.screenPaddingH,
                            bottom = 32.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(historyEntries, key = { it.id }) { entry ->
                            SlateItemCard {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = entry.command,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = dateFormatter.format(Date(entry.createdAt)),
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = entry.output,
                                        maxLines = 5,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (entry.provider.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = entry.provider,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val entryId = entry.id
                                        scope.launch(Dispatchers.IO) {
                                            historyManager.delete(entryId)
                                        }
                                        historyEntries = historyEntries.filterNot { it.id == entryId }
                                    },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DeleteOutline,
                                        contentDescription = stringResource(R.string.commands_history_delete),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
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
                    commandToDelete = null
                    scope.launch {
                        val updatedCommands = withContext(Dispatchers.IO) {
                            commandManager.removeCustomCommand(triggerToDelete)
                            commandManager.getCommands()
                        }
                        expandedIds = expandedIds - triggerToDelete
                        commands = updatedCommands
                        if (editingTrigger == triggerToDelete) closeEditor()
                    }
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
