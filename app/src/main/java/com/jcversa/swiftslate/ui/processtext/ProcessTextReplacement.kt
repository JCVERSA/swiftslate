package com.jcversa.swiftslate.ui.processtext

import java.util.concurrent.ConcurrentLinkedQueue

internal data class PendingProcessTextReplacement(
    val original: String,
    val replacement: String,
    val sourcePackage: String?,
    val createdAt: Long,
    val animateReplacement: Boolean = false
)

internal sealed interface ProcessTextEdit {
    data object Unrelated : ProcessTextEdit
    data object Replaced : ProcessTextEdit
    data class Appended(val correctedText: String) : ProcessTextEdit
}

/**
 * Results awaiting application by apps that launched ACTION_PROCESS_TEXT.
 *
 * More than one Process Text activity can be alive in the same process (for example when a user
 * launches SwiftSlate from two split-screen windows). A single AtomicReference let the newest
 * result overwrite the older one. The bounded, expiring queue keeps those requests isolated; the
 * service selects a request by source package and the exact before/after edit before consuming it.
 */
internal object ProcessTextReplacementBridge {
    private const val MAX_AGE_MS = 3_000L
    private const val MAX_PENDING = 16
    private val pending = ConcurrentLinkedQueue<PendingProcessTextReplacement>()

    fun prepare(
        original: String,
        replacement: String,
        sourcePackage: String?,
        animateReplacement: Boolean,
        now: Long
    ) {
        purgeExpired(now)
        while (pending.size >= MAX_PENDING) pending.poll()
        pending.offer(
            PendingProcessTextReplacement(
                original = original,
                replacement = replacement,
                sourcePackage = sourcePackage,
                createdAt = now,
                animateReplacement = animateReplacement
            )
        )
    }

    /** Returns live requests that may belong to [sourcePackage], without removing any. */
    fun candidates(now: Long, sourcePackage: String): List<PendingProcessTextReplacement> {
        purgeExpired(now)
        return pending.filter { request ->
            request.sourcePackage == null || request.sourcePackage == sourcePackage
        }
    }

    fun consume(request: PendingProcessTextReplacement): Boolean = pending.remove(request)

    private fun purgeExpired(now: Long) {
        // Use an explicit snapshot rather than Queue.removeIf: SwiftSlate supports API 23,
        // where the Java 8 default method is not available without core-library desugaring.
        pending.toList().forEach { request ->
            val age = now - request.createdAt
            if (age !in 0..MAX_AGE_MS) pending.remove(request)
        }
    }
}

/**
 * Distinguishes a proper selection replacement from hosts that append the result after it.
 * AccessibilityEvent indices and counts are UTF-16 offsets, matching Kotlin String indices.
 */
internal fun resolveProcessTextEdit(
    beforeText: String,
    afterText: String,
    fromIndex: Int,
    removedCount: Int,
    addedCount: Int,
    request: PendingProcessTextReplacement
): ProcessTextEdit {
    val original = request.original
    val replacement = request.replacement
    if (fromIndex < 0 || fromIndex > beforeText.length || addedCount != replacement.length) {
        return ProcessTextEdit.Unrelated
    }

    if (removedCount < 0 || fromIndex + removedCount > beforeText.length) {
        return ProcessTextEdit.Unrelated
    }
    val expectedAfter = beforeText.replaceRange(
        fromIndex,
        fromIndex + removedCount,
        replacement
    )
    if (expectedAfter != afterText) return ProcessTextEdit.Unrelated

    if (removedCount == original.length &&
        beforeText.regionMatches(fromIndex, original, 0, original.length)) {
        return ProcessTextEdit.Replaced
    }

    val originalStart = fromIndex - original.length
    if (removedCount == 0 && originalStart >= 0 &&
        beforeText.regionMatches(originalStart, original, 0, original.length)) {
        return ProcessTextEdit.Appended(
            beforeText.replaceRange(originalStart, fromIndex, replacement)
        )
    }

    return ProcessTextEdit.Unrelated
}
