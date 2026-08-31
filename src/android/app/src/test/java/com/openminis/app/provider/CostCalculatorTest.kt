package com.openminis.app.provider

import com.openminis.app.data.model.LLMUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-cost-calculator] JVM tests: JSON catalog parsing, price lookup + fuzzy
 * matching, cost math, the "unknown model → null, never zero" contract, and
 * user-override precedence.
 */
class CostCalculatorTest {

    // A representative subset loaded via parseJson (production loads from
    // assets; tests exercise the pure parser with the same schema).
    private val catalogJson = """
        {
          "gpt-4o": { "input": 2.5, "output": 10.0, "cacheRead": 1.25 },
          "gpt-4o-mini": { "input": 0.15, "output": 0.6, "cacheRead": 0.075 },
          "claude-sonnet-4-6": { "input": 3.0, "output": 15.0, "cacheRead": 0.3, "cacheWrite": 3.75 },
          "gemini-2.5-flash": { "input": 0.3, "output": 2.5, "cacheRead": 0.03 },
          "deepseek-v4-pro": { "input": 1.32, "output": 3.96, "cacheRead": 0.044 },
          "grok-4.20-0309-reasoning": { "input": 1.25, "output": 2.5, "cacheRead": 0.2 },
          "llama-4-maverick": { "input": 0.05, "output": 0.15, "cacheRead": 0.0 }
        }
    """.trimIndent()

    private val testEntries: Map<String, ModelPriceCatalog.PriceEntry> by lazy {
        ModelPriceCatalog.parseJson(catalogJson)
    }

    private fun priceFor(id: String): ModelPriceCatalog.PriceEntry? =
        ModelPriceCatalog.priceForFrom(testEntries, id)

    @Before
    fun checkParser() {
        val entries = ModelPriceCatalog.parseJson(catalogJson)
        assertEquals(7, entries.size)
        assertEquals(2.5, entries["gpt-4o"]!!.inputPerMillion, 1e-9)
        assertNull(entries["gpt-4o"]!!.cacheWritePerMillion)
        assertEquals(3.75, entries["claude-sonnet-4-6"]!!.cacheWritePerMillion!!, 1e-9)
        // Wire the production catalog path to the test JSON so
        // CostCalculator.estimateCostUsd's internal ModelPriceCatalog.priceFor
        // sees the same data.
        ModelPriceCatalog.loader = { catalogJson }
        ModelPriceCatalog.reload()
    }

    @Test
    fun `exact model id resolves price`() {
        val p = priceFor("gpt-4o")
        assertNotNull(p)
        assertEquals(2.5, p!!.inputPerMillion, 1e-9)
        assertEquals(10.0, p.outputPerMillion, 1e-9)
    }

    @Test
    fun `vendor prefix strips to bare slug`() {
        assertEquals(priceFor("gpt-4o"), priceFor("openai/gpt-4o"))
    }

    @Test
    fun `unknown model returns null not zero`() {
        assertNull(priceFor("totally-unknown-model"))
        assertNull(CostCalculator.estimateCostUsd("totally-unknown-model", LLMUsage(1000, 1000)))
    }

    @Test
    fun `blank model id returns null`() {
        assertNull(priceFor(""))
        assertNull(priceFor("   "))
    }

    @Test
    fun `case insensitive fallback`() {
        assertEquals(priceFor("gpt-4o"), priceFor("GPT-4O"))
    }

    @Test
    fun `date suffix is stripped for deepseek release ids`() {
        val p = priceFor("deepseek-v4-pro-0813")
        assertNotNull(p)
        assertEquals(1.32, p!!.inputPerMillion, 1e-9)
        assertEquals(3.96, p.outputPerMillion, 1e-9)
        assertEquals(0.044, p.cacheReadPerMillion!!, 1e-9)
    }

    @Test
    fun `date suffix with vendor prefix strips too`() {
        val p = priceFor("deepseek/deepseek-v4-pro-0813")
        assertNotNull(p)
        assertEquals(1.32, p!!.inputPerMillion, 1e-9)
    }

    @Test
    fun `non-numeric suffix is never stripped`() {
        assertNotNull(priceFor("grok-4.20-0309-reasoning"))
    }

    @Test
    fun `cost math fresh input and output`() {
        val cost = CostCalculator.estimateCostUsd("gpt-4o", LLMUsage(inputTokens = 1_000_000, outputTokens = 500_000))
        assertNotNull(cost)
        assertEquals(7.5, cost!!, 1e-9)
    }

    @Test
    fun `cost math includes anthropic cache pricing`() {
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
    fun `negative token counts clamped to zero`() {
        val cost = CostCalculator.estimateCostUsd("gpt-4o", LLMUsage(inputTokens = -500, outputTokens = -100))
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
        val cost = CostCalculator.estimateCostUsd("gpt-4o-mini", LLMUsage(3_000, 300))
        assertNotNull(cost)
        val expected = 3_000 * 0.15 / 1e6 + 300 * 0.6 / 1e6
        assertEquals(expected, cost!!, 1e-12)
        assertTrue(cost < 0.01)
    }

    @Test
    fun `user overrides win over catalog`() {
        // deepseek-v4-pro catalog: in 1.32 / out 3.96. Override to a relay
        // station's actual price (in 2.0 / out 6.0).
        val cost = CostCalculator.estimateCostUsd(
            "deepseek-v4-pro-0813",
            LLMUsage(1_000_000, 100_000),
            inputPricePerMillion = 2.0,
            outputPricePerMillion = 6.0,
        )
        assertNotNull(cost)
        // 1M * 2.0/1M + 0.1M * 6.0/1M = 2.0 + 0.6 = 2.6
        assertEquals(2.6, cost!!, 1e-9)
    }

    @Test
    fun `partial override falls back to catalog`() {
        // Only input override (no output) → not a complete synthetic entry,
        // falls back to catalog price entirely.
        val cost = CostCalculator.estimateCostUsd(
            "gpt-4o",
            LLMUsage(1_000_000, 100_000),
            inputPricePerMillion = 9.9,
        )
        assertNotNull(cost)
        // catalog gpt-4o: 2.5 in + 10.0 out → 1M in + 0.1M out = 2.5 + 1.0 = 3.5
        assertEquals(3.5, cost!!, 1e-9)
    }

    @Test
    fun `override works even for unknown catalog model`() {
        // Model not in catalog at all, but user supplied both prices.
        val cost = CostCalculator.estimateCostUsd(
            "my-relay/custom-model",
            LLMUsage(1_000_000, 0),
            inputPricePerMillion = 1.0,
            outputPricePerMillion = 2.0,
        )
        assertNotNull(cost)
        assertEquals(1.0, cost!!, 1e-9)
    }

    @Test
    fun `malformed json yields empty catalog`() {
        assertEquals(0, ModelPriceCatalog.parseJson("{ not valid json").size)
        assertEquals(0, ModelPriceCatalog.parseJson("").size)
    }

    @Test
    fun `entry missing required prices is skipped`() {
        val entries = ModelPriceCatalog.parseJson("""{ "broken": { "cacheRead": 0.5 } }""")
        assertEquals(0, entries.size)
    }
}
