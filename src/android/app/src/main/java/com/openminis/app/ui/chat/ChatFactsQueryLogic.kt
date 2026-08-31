package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage

/**
 * [feat/facts-query-relevance] Pure extraction of query tokens from the most
 * recent REAL user input, used to make structured-facts retrieval query-aware.
 *
 * Lives in its own file (like ChatCompactionLogic.kt / ChatMessageJson.kt) so
 * it stays JVM-testable without dragging in ChatViewModel's Android/Compose
 * dependencies — the tokenizer is injected as a lambda.
 */

/**
 * Extract query tokens for facts retrieval from the most recent REAL user
 * input in [history].
 *
 * Walks the history tail backwards and takes the first USER-role message that
 * is a genuine typed user turn, NOT a synthetic tool_result / Continue
 * reminder (both ride USER role with `content == ""` + only ToolResult parts,
 * or a "The user stopped…" Text part). Returns the tokenized, lowercase,
 * deduplicated content of that message, or an empty list when there is no
 * real user input yet (cold start / pure-resume).
 *
 * Tokenization is delegated to [segmenter] so the function stays JVM-testable
 * (the caller owns the Android-bound TextSegmenter). An empty result is the
 * "no signal" sentinel: the injection site falls back to pure recency
 * ordering (unchanged legacy behavior).
 */
internal fun extractQueryTokens(
    history: List<LLMMessage>,
    segmenter: (String) -> List<String>,
): List<String> {
    for (msg in history.asReversed()) {
        if (msg.role != LLMMessage.Role.USER) continue
        // A real typed user turn carries its text in `content`. Synthetic
        // USER-role messages (tool_result: content="" + only ToolResult parts;
        // Continue reminder: content="" + a "The user stopped…" Text part)
        // are skipped.
        val textParts = msg.contentParts.filterIsInstance<AgentContentPart.Text>()
        val toolResultParts = msg.contentParts.filterIsInstance<AgentContentPart.ToolResult>()
        val isToolResultOnly = msg.content.isBlank() &&
            toolResultParts.isNotEmpty() &&
            textParts.isEmpty()
        if (isToolResultOnly) continue
        val text = msg.content.ifBlank {
            textParts.joinToString(" ") { it.text }
        }.trim()
        if (text.isBlank()) continue
        // Continue-reminder synthetic text must not become query tokens.
        if (text.contains("The user stopped the previous response")) continue
        return segmenter(text)
            .map { it.lowercase().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
    return emptyList()
}
