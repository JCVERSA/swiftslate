package com.jcversa.swiftslate.model

/**
 * Conservative filter for OpenAI-compatible /models responses. Providers can
 * expose embeddings, rerankers, speech and moderation models alongside chat
 * models; those cannot service SwiftSlate's text transformation request.
 */
object OpenAIModels {
    private val NON_CHAT_SUBSTRINGS = listOf(
        "embedding", "embed-", "rerank", "moderation", "moderate",
        "whisper", "transcription", "-tts", "text-to-speech", "guard",
        "safety", "classif"
    )

    fun isChatCandidate(id: String): Boolean =
        NON_CHAT_SUBSTRINGS.none { id.contains(it, ignoreCase = true) }
}
