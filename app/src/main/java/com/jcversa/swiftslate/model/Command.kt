package com.jcversa.swiftslate.model

import androidx.compose.runtime.Immutable

enum class CommandType {
    AI, TEXT_REPLACER
}

@Immutable
data class Command(
    val trigger: String,
    val prompt: String,
    val isBuiltIn: Boolean = false,
    val type: CommandType = CommandType.AI,
    /** Alternative triggers for this command. Persisted with custom commands only. */
    val aliases: List<String> = emptyList()
)

data class CommandMatch(
    val command: Command,
    val matchedTrigger: String
)
