package com.openminis.app.provider

import com.openminis.app.data.model.LLMUsage

/**
 * [T-cost-calculator] Pure function: token usage × price → estimated USD.
 *
 * Mirrors LiteLLM's `completion_cost` shape (tokens × per-token price) but
 * trimmed to the dimensions RikkaMinis records. Price resolution order:
 *
 *  1. User overrides ([inputPricePerMillion]/[outputPricePerMillion]) — the
 *     only correct source for proxy/relay models (e.g. DeepSeek relay stations)
 *     whose real price isn't in any public catalog.
 *  2. Built-in catalog ([ModelPriceCatalog], assets/model_prices.json).
 *
 * Semantics:
 *  - `inputTokens` is FRESH input (cache portion subtracted by the parsers).
 *    Cache reads/writes are priced by their own (cheaper) rates when known.
 *  - No price available → null. Never fabricate a $0.00 (same "don't fake
 *    precision" rule as AgentExecutionBudget's null token counts).
 *  - Negative/clamped inputs are treated as 0 (defensive).
 *
 * JVM-pure — unit-testable in the sandbox.
 */
object CostCalculator {

    /**
     * Estimated cost in USD for one LLM call, or null when no price is known.
     * [inputPricePerMillion]/[outputPricePerMillion] (user overrides) win over
     * the built-in catalog.
     */
    fun estimateCostUsd(
        modelId: String,
        usage: LLMUsage,
        inputPricePerMillion: Double? = null,
        outputPricePerMillion: Double? = null,
    ): Double? {
        // Resolve a PriceEntry: overrides (if both present) form a synthetic
        // entry with no cache pricing; otherwise the catalog.
        val price: ModelPriceCatalog.PriceEntry = when {
            inputPricePerMillion != null && outputPricePerMillion != null ->
                ModelPriceCatalog.PriceEntry(
                    inputPerMillion = inputPricePerMillion,
                    outputPerMillion = outputPricePerMillion,
                )
            else -> ModelPriceCatalog.priceFor(modelId) ?: return null
        }

        val freshInput = usage.inputTokens.coerceAtLeast(0)
        val output = usage.outputTokens.coerceAtLeast(0)
        val cacheRead = (usage.cacheReadInputTokens ?: 0).coerceAtLeast(0)
        val cacheWrite = (usage.cacheCreationInputTokens ?: 0).coerceAtLeast(0)

        val inputCost = freshInput * price.inputPerMillion / 1_000_000.0
        val outputCost = output * price.outputPerMillion / 1_000_000.0
        val cacheReadCost = cacheRead * (price.cacheReadPerMillion ?: price.inputPerMillion) / 1_000_000.0
        val cacheWriteCost = cacheWrite * (price.cacheWritePerMillion ?: price.inputPerMillion) / 1_000_000.0

        return inputCost + outputCost + cacheReadCost + cacheWriteCost
    }
}
