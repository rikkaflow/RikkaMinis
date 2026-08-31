package com.openminis.app.data.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * [T-cost-aggregate] Cost dimension of UsageAggregator: persisted-key
 * preference, legacy-row back-computation, unknown-model null semantics.
 */
class UsageAggregatorCostTest {

    private val utcDayFormat: (Long) -> String = { ms ->
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        fmt.format(Date(ms))
    }

    private fun row(
        modelId: String = "gpt-4o",
        json: String,
        createdAtMs: Long = 1_000L,
        sessionId: String = "sess-1",
    ) = UsageRow(modelId, json, createdAtMs, sessionId)

    @Test
    fun `persisted estimatedCostUsd key is summed`() {
        val rows = listOf(
            row(json = """{"inputTokens":1000,"outputTokens":100,"estimatedCostUsd":0.0035}"""),
            row(json = """{"inputTokens":2000,"outputTokens":200,"estimatedCostUsd":0.0070}"""),
        )
        val result = UsageAggregator.aggregate(rows, dayFormat = utcDayFormat)
        val stats = result["gpt-4o"]!!
        assertNotNull(stats.estimatedCostUsd)
        assertEquals(0.0105, stats.estimatedCostUsd!!, 1e-12)
    }

    @Test
    fun `legacy rows without the key are back-computed from current catalog`() {
        // gpt-4o: 2.5 in / 10.0 out per 1M → 1M in + 0.1M out = 2.5 + 1.0 = 3.5
        val rows = listOf(
            row(json = """{"inputTokens":1000000,"outputTokens":100000}"""),
        )
        val result = UsageAggregator.aggregate(rows, dayFormat = utcDayFormat)
        val stats = result["gpt-4o"]!!
        assertNotNull(stats.estimatedCostUsd)
        assertEquals(3.5, stats.estimatedCostUsd!!, 1e-9)
    }

    @Test
    fun `unknown model yields null cost not zero`() {
        val rows = listOf(
            row(modelId = "mystery-model", json = """{"inputTokens":5000,"outputTokens":500}"""),
        )
        val result = UsageAggregator.aggregate(rows, dayFormat = utcDayFormat)
        assertNull(result["mystery-model"]!!.estimatedCostUsd)
    }

    @Test
    fun `mixed rows prefer persisted key and back-compute the rest`() {
        // One legacy (no key) + one new (with key) for the same model.
        // Legacy: 1M in gpt-4o → 2.5. Persisted: 0.5. Total 3.0.
        val rows = listOf(
            row(json = """{"inputTokens":1000000,"outputTokens":0}""", createdAtMs = 1_000L),
            row(json = """{"inputTokens":0,"outputTokens":0,"estimatedCostUsd":0.5}""", createdAtMs = 2_000L),
        )
        val result = UsageAggregator.aggregate(rows, dayFormat = utcDayFormat)
        assertEquals(3.0, result["gpt-4o"]!!.estimatedCostUsd!!, 1e-9)
    }

    @Test
    fun `cache tokens included in back-computed cost`() {
        // claude-sonnet-4-6: in 3.0 / out 15.0 / cacheRead 0.3 / cacheWrite 3.75
        // 100k fresh in + 400k cacheRead + 50k cacheWrite + 10k out
        // = 0.3 + 0.12 + 0.1875 + 0.15 = 0.7575
        val rows = listOf(
            row(
                modelId = "claude-sonnet-4-6",
                json = """{"inputTokens":100000,"outputTokens":10000,"cacheReadTokens":400000,"cacheCreationTokens":50000}""",
            ),
        )
        val result = UsageAggregator.aggregate(rows, dayFormat = utcDayFormat)
        assertEquals(0.7575, result["claude-sonnet-4-6"]!!.estimatedCostUsd!!, 1e-9)
    }

    @Test
    fun `malformed estimatedCostUsd falls back to back-computation`() {
        // estimatedCostUsd present but not a number (NaN guard) → recompute.
        // 100k in gpt-4o → 0.25
        val rows = listOf(
            row(json = """{"inputTokens":100000,"outputTokens":0,"estimatedCostUsd":"garbage"}"""),
        )
        val result = UsageAggregator.aggregate(rows, dayFormat = utcDayFormat)
        assertEquals(0.25, result["gpt-4o"]!!.estimatedCostUsd!!, 1e-9)
    }

    @Test
    fun `zero-token unknown-model rows stay null`() {
        // Even zero usage doesn't make an unknown model "free" — null means
        // "price unknown", and the UI must show unknown, not $0.
        val rows = listOf(
            row(modelId = "mystery-model", json = """{"inputTokens":0,"outputTokens":0}"""),
        )
        val result = UsageAggregator.aggregate(rows, dayFormat = utcDayFormat)
        assertNull(result["mystery-model"]!!.estimatedCostUsd)
    }

    @Test
    fun `openrouter prefixed model ids resolve through slug stripping`() {
        // usage_model_id may be recorded as "openai/gpt-4o" for OpenRouter
        // instances — the catalog strips the vendor prefix.
        val rows = listOf(
            row(modelId = "openai/gpt-4o", json = """{"inputTokens":1000000,"outputTokens":0}"""),
        )
        val result = UsageAggregator.aggregate(rows, dayFormat = utcDayFormat)
        assertEquals(2.5, result["openai/gpt-4o"]!!.estimatedCostUsd!!, 1e-9)
    }
}
