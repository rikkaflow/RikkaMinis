package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [feat/facts-query-relevance] JVM tests for extractQueryTokens — the pure
 * "last real user input → query tokens" extraction. Pins three behaviors:
 * 1) a real typed user turn yields its tokens;
 * 2) synthetic tool_result / Continue-reminder USER messages are skipped;
 * 3) no real input → empty list (the no-signal sentinel).
 *
 * The Android-bound TextSegmenter is injected as a lambda, so this test runs
 * on the JVM with no Android/ICU dependency.
 */
class ChatViewModelFactsQueryTokensTest {

    private fun user(
        content: String,
        parts: List<AgentContentPart> = emptyList(),
    ) = LLMMessage(role = LLMMessage.Role.USER, content = content, contentParts = parts)

    private fun assistant() =
        LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "ok", contentParts = emptyList())

    // Tokenizer: split on whitespace, lowercase, drop empties — a deterministic
    // stand-in for TextSegmenter in the JVM.
    private val splitter: (String) -> List<String> = { text ->
        text.split(Regex("\\s+")).map { it.lowercase() }.filter { it.isNotEmpty() }
    }

    @Test
    fun `real typed user turn yields its tokens`() {
        val history = listOf(
            assistant(),
            user("帮我 修 Kotlin 编译 错误"),
        )
        val tokens = extractQueryTokens(history, splitter)
        assertTrue(tokens.isNotEmpty())
        assertTrue(tokens.any { it.contains("kotlin") })
    }

    @Test
    fun `tool_result tail is skipped — falls back to earlier real input`() {
        val toolResultParts = listOf<AgentContentPart>(
            AgentContentPart.ToolResult("t1", "shell_execute", "output", false),
        )
        val history = listOf(
            user("看看 这个 项目 的 测试"),
            assistant(),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = toolResultParts,
            ),
        )
        val tokens = extractQueryTokens(history, splitter)
        // "shell_execute output" (tool_result) must NOT contribute tokens.
        assertTrue(tokens.none { it.contains("output") || it.contains("shell_execute") })
        assertTrue(tokens.any { it.contains("项目") || it.contains("测试") })
    }

    @Test
    fun `continue reminder tail is skipped`() {
        val continueParts = listOf<AgentContentPart>(
            AgentContentPart.Text("The user stopped the previous response"),
        )
        val history = listOf(
            user("先 分析 一下 这个 bug"),
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = continueParts,
            ),
        )
        val tokens = extractQueryTokens(history, splitter)
        assertTrue(tokens.none { it.contains("stopped") })
        assertTrue(tokens.any { it.contains("bug") || it.contains("分析") })
    }

    @Test
    fun `no real user input returns empty list`() {
        val toolResultParts = listOf<AgentContentPart>(
            AgentContentPart.ToolResult("t1", "shell_execute", "output", false),
        )
        val onlyToolResult = listOf(
            LLMMessage(
                role = LLMMessage.Role.USER,
                content = "",
                contentParts = toolResultParts,
            ),
        )
        assertEquals(emptyList<String>(), extractQueryTokens(onlyToolResult, splitter))
        assertEquals(emptyList<String>(), extractQueryTokens(emptyList(), splitter))
    }

    @Test
    fun `empty tokens sentinel does not crash the injection fallback`() {
        // The injection site must treat empty as "no signal" → recency fallback.
        // Here we just assert the sentinel contract the caller relies on.
        assertEquals(emptyList<String>(), extractQueryTokens(listOf(assistant()), splitter))
    }
}
