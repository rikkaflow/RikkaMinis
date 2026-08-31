package com.openminis.app.provider

import com.openminis.app.data.model.LLMUsage

/**
 * [T-cost-calculator] Pure function: token usage × catalog price → estimated USD.
 *
 * Mirrors LiteLLM's `completion_cost` shape (tokens × per-token price) but
 * trimmed to the dimensions RikkaMinis records. Semantics:
 *
 *  - `inputTokens` is FRESH input (cache portion subtracted by the parsers —
 *    see the Usage chunk handling in ChatViewModel). Cache reads/writes are
 *    priced by their own (cheaper) rates when the catalog has them.
 *  - Unknown model → null. Never fabricate a $0.00 (same "don't fake
 *    precision" rule as AgentExecutionBudget's null token counts).
 *  - Negative/clamped inputs are treated as 0 (defensive; JSON rows may be
 *    malformed).
 *
 * JVM-pure — no Android dependency — unit-testable in the sandbox.
 */
object CostCalculator {

    /**
     * Estimated cost in USD for one LLM call, or null when [modelId] has no
     * price entry (cost unknown). Result is a raw Double — callers format it.
     */
    fun estimateCostUsd(modelId: String, usage: LLMUsage): Double? {
        val price = ModelPriceCatalog.priceFor(modelId) ?: return null

        val freshInput = usage.inputTokens.coerceAtLeast(0)
        val output = usage.outputTokens.coerceAtLeast(0)
        val cacheRead = (usage.cacheReadInputTokens ?: 0).coerceAtLeast(0)
        val cacheWrite = (usage.cacheCreationInputTokens ?: 0).coerceAtLeast(0)

        val inputCost = freshInput * price.inputPerMillion / 1_000_000.0
        val outputCost = output * price.outputPerMillion / 1_000_000.0
        // Cache-read: cheaper than fresh input when listed; when the catalog
        // has no cacheRead price the provider charges normal input price —
        // but the tokens were already counted in inputTokens? No: parsers
        // subtract cache from inputTokens, so price them at input rate as the
        // closest approximation.
        val cacheReadCost = cacheRead * (price.cacheReadPerMillion ?: price.inputPerMillion) / 1_000_000.0
        // Cache-write (Anthropic): 1.25× input when listed; else input rate.
        val cacheWriteCost = cacheWrite * (price.cacheWritePerMillion ?: price.inputPerMillion) / 1_000_000.0

        return inputCost + outputCost + cacheReadCost + cacheWriteCost
    }
}
