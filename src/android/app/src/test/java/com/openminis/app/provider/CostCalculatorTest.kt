package com.openminis.app.provider

import com.openminis.app.data.model.LLMUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-cost-calculator] JVM tests: price lookup + cost math + the
 * "unknown model → null, never zero" contract.
 */
class CostCalculatorTest {

    @Test
    fun `exact model id resolves price`() {
        val p = ModelPriceCatalog.priceFor("gpt-4o")
        assertNotNull(p)
        assertEquals(2.5, p!!.inputPerMillion, 1e-9)
        assertEquals(10.0, p.outputPerMillion, 1e-9)
    }

    @Test
    fun `openrouter vendor prefix strips to bare slug`() {
        // openai/gpt-4o → gpt-4o
        assertEquals(ModelPriceCatalog.priceFor("gpt-4o"), ModelPriceCatalog.priceFor("openai/gpt-4o"))
        // anthropic/claude-sonnet-4 → claude-sonnet-4 (not in catalog → but
        // claude-sonnet-4-6 IS; bare "claude-sonnet-4" should be unknown)
        assertNull(ModelPriceCatalog.priceFor("anthropic/claude-sonnet-4"))
    }

    @Test
    fun `unknown model returns null not zero`() {
        assertNull(ModelPriceCatalog.priceFor("totally-unknown-model"))
        assertNull(CostCalculator.estimateCostUsd("totally-unknown-model", LLMUsage(1000, 1000)))
    }

    @Test
    fun `blank model id returns null`() {
        assertNull(ModelPriceCatalog.priceFor(""))
        assertNull(ModelPriceCatalog.priceFor("   "))
    }

    @Test
    fun `case insensitive fallback`() {
        assertEquals(ModelPriceCatalog.priceFor("gpt-4o"), ModelPriceCatalog.priceFor("GPT-4O"))
    }

    @Test
    fun `cost math fresh input and output`() {
        // gpt-4o: in 2.5 / out 10.0 per 1M
        val cost = CostCalculator.estimateCostUsd("gpt-4o", LLMUsage(inputTokens = 1_000_000, outputTokens = 500_000))
        assertNotNull(cost)
        // 1M * 2.5/1M + 0.5M * 10/1M = 2.5 + 5.0 = 7.5
        assertEquals(7.5, cost!!, 1e-9)
    }

    @Test
    fun `cost math includes anthropic cache pricing`() {
        // claude-sonnet-4-6: in 3.0 / out 15.0 / cacheRead 0.3 / cacheWrite 3.75
        val usage = LLMUsage(
            inputTokens = 100_000,
            outputTokens = 10_000,
            cacheCreationInputTokens = 50_000,
            cacheReadInputTokens = 200_000,
        )
        val cost = CostCalculator.estimateCostUsd("claude-sonnet-4-6", usage)
        assertNotNull(cost)
        val expected = 100_000 * 3.0 / 1e6 + 10_000 * 15.0 / 1e6 +
            200_000 * 0.3 / 1e6 + 50_000 * 3.75 / 1e6
        assertEquals(expected, cost!!, 1e-9)
    }

    @Test
    fun `cache tokens priced at input rate when no cache price listed`() {
        // gemini-2.5-flash: in 0.3 / out 2.5 / cacheRead 0.03, no cacheWrite
        // → cacheWrite tokens fall back to input price 0.3
        val usage = LLMUsage(
            inputTokens = 0,
            outputTokens = 0,
            cacheCreationInputTokens = 1_000_000,
        )
        val cost = CostCalculator.estimateCostUsd("gemini-2.5-flash", usage)
        assertNotNull(cost)
        // cacheWrite fallback: 1M * 0.3/1M = 0.3 (not 3.75x — catalog has none)
        assertEquals(0.3, cost!!, 1e-9)
    }

    @Test
    fun `negative token counts clamped to zero`() {
        // Defensive: malformed rows shouldn't produce negative costs.
        val cost = CostCalculator.estimateCostUsd(
            "gpt-4o",
            LLMUsage(inputTokens = -500, outputTokens = -100),
        )
        assertNotNull(cost)
        assertEquals(0.0, cost!!, 1e-9)
    }

    @Test
    fun `zero usage yields zero cost for known model`() {
        val cost = CostCalculator.estimateCostUsd("gpt-4o", LLMUsage(0, 0))
        assertNotNull(cost)
        assertEquals(0.0, cost!!, 1e-9)
    }

    @Test
    fun `realistic small call is sub-cent`() {
        // 3k in / 300 out on gpt-4o-mini (0.15 / 0.6)
        val cost = CostCalculator.estimateCostUsd("gpt-4o-mini", LLMUsage(3_000, 300))
        assertNotNull(cost)
        val expected = 3_000 * 0.15 / 1e6 + 300 * 0.6 / 1e6
        assertEquals(expected, cost!!, 1e-12)
        assertTrue(cost < 0.01)
    }

    @Test
    fun `all builtin catalog models resolve`() {
        // Every entry must at least resolve itself (sanity for hand-typed table)
        for (id in listOf(
            "claude-fable-5", "claude-opus-4-8", "claude-opus-4-6", "claude-sonnet-5",
            "claude-sonnet-4-6", "claude-haiku-4-5",
            "gemini-3-pro-preview", "gemini-3-flash-preview", "gemini-2.5-pro",
            "gemini-2.5-flash", "gemini-2.5-flash-lite",
            "gpt-5.5", "gpt-5.3-codex", "gpt-5.2-codex", "gpt-5.1-codex-max", "gpt-5.2",
            "gpt-4o", "gpt-4o-mini", "o3", "o4-mini", "codex-mini-latest",
            "llama-4-maverick",
            "grok-4.5", "grok-4.3", "grok-4.20-0309-reasoning", "grok-4.20-0309-non-reasoning",
            "grok-4.20-multi-agent-0309", "grok-build-0.1", "grok-3-mini", "grok-3-mini-fast",
            "grok-composer-2.5-fast", "grok-4-fast", "grok-4-fast-non-reasoning", "grok-code-fast-1",
            "kimi-k3", "kimi-k2",
        )) {
            assertNotNull("catalog missing: $id", ModelPriceCatalog.priceFor(id))
        }
    }
}
