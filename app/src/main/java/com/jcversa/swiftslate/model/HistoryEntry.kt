package com.jcversa.swiftslate.model

/** A locally stored command result. The input and output are only persisted when history is enabled. */
data class HistoryEntry(
    val id: String,
    val command: String,
    val input: String,
    val output: String,
    val createdAt: Long,
    val provider: String
)
