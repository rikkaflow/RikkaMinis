package com.openminis.app.conversation

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-compact-cache] JVM tests for the exact-match compaction summary cache:
 * hit/miss semantics, invalidation on every key component, boundedness.
 */
class CompactSummaryCacheTest {

    @After
    fun tearDown() {
        CompactSummaryCache.clear()
    }

    @Test
    fun `store then lookup hits with identical inputs`() {
        CompactSummaryCache.store(
            modelId = "gpt-4o",
            systemPrompt = "You are a compaction engine.",
            previousSummary = null,
            transcript = "user: hello\nassistant: hi",
            summaryText = "User greeted; assistant replied.",
            outputTokensEstimate = 10,
        )
        val hit = CompactSummaryCache.lookup(
            modelId = "gpt-4o",
            systemPrompt = "You are a compaction engine.",
            previousSummary = null,
            transcript = "user: hello\nassistant: hi",
        )
        assertNotNull(hit)
        assertEquals("User greeted; assistant replied.", hit!!.summaryText)
        assertEquals(10, hit.outputTokensEstimate)
    }

    @Test
    fun `different transcript misses`() {
        CompactSummaryCache.store(
            modelId = "gpt-4o", systemPrompt = "s", previousSummary = null,
            transcript = "A", summaryText = "summary-A", outputTokensEstimate = 1,
        )
        assertNull(CompactSummaryCache.lookup("gpt-4o", "s", null, "B"))
    }

    @Test
    fun `different model misses`() {
        CompactSummaryCache.store(
            modelId = "gpt-4o", systemPrompt = "s", previousSummary = null,
            transcript = "A", summaryText = "summary-A", outputTokensEstimate = 1,
        )
        assertNull(CompactSummaryCache.lookup("claude-sonnet-4-6", "s", null, "A"))
    }

    @Test
    fun `different system prompt misses`() {
        CompactSummaryCache.store(
            modelId = "gpt-4o", systemPrompt = "s1", previousSummary = null,
            transcript = "A", summaryText = "summary-A", outputTokensEstimate = 1,
        )
        assertNull(CompactSummaryCache.lookup("gpt-4o", "s2", null, "A"))
    }

    @Test
    fun `different previous summary misses`() {
        CompactSummaryCache.store(
            modelId = "gpt-4o", systemPrompt = "s", previousSummary = null,
            transcript = "A", summaryText = "summary-A", outputTokensEstimate = 1,
        )
        // null vs "prev" must not hit — previousSummary is part of the key.
        assertNull(CompactSummaryCache.lookup("gpt-4o", "s", "prev", "A"))
    }

    @Test
    fun `null model or blank transcript are noop`() {
        CompactSummaryCache.store(
            modelId = null, systemPrompt = "s", previousSummary = null,
            transcript = "A", summaryText = "x", outputTokensEstimate = 1,
        )
        assertEquals(0, CompactSummaryCache.size())
        CompactSummaryCache.store(
            modelId = "gpt-4o", systemPrompt = "s", previousSummary = null,
            transcript = "  ", summaryText = "x", outputTokensEstimate = 1,
        )
        assertEquals(0, CompactSummaryCache.size())
        // lookup with blank inputs also misses
        assertNull(CompactSummaryCache.lookup(null, "s", null, "A"))
        assertNull(CompactSummaryCache.lookup("gpt-4o", "s", null, ""))
    }

    @Test
    fun `blank summary text is not stored`() {
        CompactSummaryCache.store(
            modelId = "gpt-4o", systemPrompt = "s", previousSummary = null,
            transcript = "A", summaryText = "   ", outputTokensEstimate = 1,
        )
        assertEquals(0, CompactSummaryCache.size())
    }

    @Test
    fun `null previous summary and empty string previous summary are equivalent`() {
        // Key normalization: null → "" — so storing with null and looking up
        // with "" (or vice versa) MUST hit. Compaction callers pass either
        // form depending on whether a prior summary exists.
        CompactSummaryCache.store(
            modelId = "gpt-4o", systemPrompt = "s", previousSummary = null,
            transcript = "A", summaryText = "summary-A", outputTokensEstimate = 1,
        )
        assertNotNull(CompactSummaryCache.lookup("gpt-4o", "s", "", "A"))
    }

    @Test
    fun `cache stays bounded under many distinct entries`() {
        for (i in 0 until 100) {
            CompactSummaryCache.store(
                modelId = "gpt-4o", systemPrompt = "s", previousSummary = null,
                transcript = "transcript-$i", summaryText = "summary-$i",
                outputTokensEstimate = 1,
            )
        }
        // Soft bound: MAX_ENTRIES=8, hard ceiling 2*MAX=16 after trim.
        // 100 distinct inserts must not leave 100 entries.
        assert(CompactSummaryCache.size() <= 16) {
            "cache should stay bounded, was ${CompactSummaryCache.size()}"
        }
    }

    @Test
    fun `negative token estimate clamped to zero`() {
        CompactSummaryCache.store(
            modelId = "gpt-4o", systemPrompt = "s", previousSummary = null,
            transcript = "A", summaryText = "x", outputTokensEstimate = -5,
        )
        val hit = CompactSummaryCache.lookup("gpt-4o", "s", null, "A")
        assertNotNull(hit)
        assertEquals(0, hit!!.outputTokensEstimate)
    }
}
