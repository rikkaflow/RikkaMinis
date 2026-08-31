package com.openminis.app.provider

/**
 * [T-cost-catalog] Built-in per-model price catalog (USD per 1M tokens).
 *
 * Source: LiteLLM's community-maintained `model_prices_and_context_window.json`
 * (MIT-licensed, BerriAI/litellm), extracted 2026-08-31 and hand-trimmed to the
 * models this app's built-in catalog actually surfaces (LLMModel.allModels).
 * Prices drift over time — this is an ESTIMATE for display purposes, not billing.
 *
 * Design rules (mirrors AgentExecutionBudget's "don't fabricate" discipline):
 *  - Unknown model → [PriceEntry] absent → cost shows as "unknown", never 0.
 *    A wrong "$0.00" is worse than an honest null.
 *  - Lookup is fuzzy: exact id first, then OpenRouter-prefixed ids
 *    ("openai/gpt-4o") strip to the bare slug ("gpt-4o").
 *  - Pure object, no Android dependency — JVM-testable.
 */
object ModelPriceCatalog {

    /**
     * @param inputPerMillion  USD per 1M fresh input tokens.
     * @param outputPerMillion USD per 1M output tokens.
     * @param cacheReadPerMillion USD per 1M cache-read input tokens (provider prompt cache hits).
     * @param cacheWritePerMillion USD per 1M cache-creation tokens (Anthropic cache writes; null = provider charges normal input price or has no cache-write pricing).
     */
    data class PriceEntry(
        val inputPerMillion: Double,
        val outputPerMillion: Double,
        val cacheReadPerMillion: Double? = null,
        val cacheWritePerMillion: Double? = null,
    )

    private val entries: Map<String, PriceEntry> = mapOf(
        // ── Anthropic ──────────────────────────────────────────────
        "claude-fable-5" to PriceEntry(10.0, 50.0, 1.0, 12.5),
        "claude-opus-4-8" to PriceEntry(5.0, 25.0, 0.5, 6.25),
        "claude-opus-4-6" to PriceEntry(5.0, 25.0, 0.5, 6.25),
        "claude-sonnet-5" to PriceEntry(2.0, 10.0, 0.2, 2.5),
        "claude-sonnet-4-6" to PriceEntry(3.0, 15.0, 0.3, 3.75),
        "claude-haiku-4-5" to PriceEntry(1.0, 5.0, 0.1, 1.25),
        // ── Google Gemini ─────────────────────────────────────────
        "gemini-3-pro-preview" to PriceEntry(2.0, 12.0, 0.2),
        "gemini-3-flash-preview" to PriceEntry(0.5, 3.0, 0.05),
        "gemini-2.5-pro" to PriceEntry(1.25, 10.0, 0.125),
        "gemini-2.5-flash" to PriceEntry(0.3, 2.5, 0.03),
        "gemini-2.5-flash-lite" to PriceEntry(0.1, 0.4, 0.01),
        // ── OpenAI ────────────────────────────────────────────────
        "gpt-5.5" to PriceEntry(5.0, 30.0, 0.5),
        "gpt-5.3-codex" to PriceEntry(1.75, 14.0, 0.175),
        "gpt-5.2-codex" to PriceEntry(1.75, 14.0, 0.175),
        "gpt-5.1-codex-max" to PriceEntry(1.25, 10.0, 0.125),
        "gpt-5.2" to PriceEntry(1.75, 14.0, 0.175),
        "gpt-4o" to PriceEntry(2.5, 10.0, 1.25),
        "gpt-4o-mini" to PriceEntry(0.15, 0.6, 0.075),
        "o3" to PriceEntry(2.0, 8.0, 0.5),
        "o4-mini" to PriceEntry(1.1, 4.4, 0.275),
        "codex-mini-latest" to PriceEntry(1.5, 6.0, 0.375),
        // ── OpenRouter (bare slug after "vendor/") ───────────────
        // anthropic/claude-sonnet-4 resolves to the Anthropic entry above
        // via slug-strip; same for google/gemini-2.5-flash and openai/gpt-4o.
        "llama-4-maverick" to PriceEntry(0.05, 0.15, 0.0), // openrouter meta-llama proxy pricing
        // ── xAI Grok ──────────────────────────────────────────────
        "grok-4.5" to PriceEntry(2.0, 6.0, 0.3),
        "grok-4.3" to PriceEntry(1.25, 2.5, 0.2),
        "grok-4.20-0309-reasoning" to PriceEntry(1.25, 2.5, 0.2),
        "grok-4.20-0309-non-reasoning" to PriceEntry(1.25, 2.5, 0.2),
        "grok-4.20-multi-agent-0309" to PriceEntry(1.25, 2.5, 0.2),
        "grok-build-0.1" to PriceEntry(1.0, 2.0, 0.2),
        "grok-3-mini" to PriceEntry(1.25, 2.5, 0.2),
        "grok-3-mini-fast" to PriceEntry(1.25, 2.5, 0.2),
        "grok-composer-2.5-fast" to PriceEntry(1.25, 2.5, 0.2),
        "grok-4-fast" to PriceEntry(1.25, 2.5, 0.2),
        "grok-4-fast-non-reasoning" to PriceEntry(1.25, 2.5, 0.2),
        "grok-code-fast-1" to PriceEntry(1.0, 2.0, 0.2),
        // ── Kimi (Moonshot) ───────────────────────────────────────
        "kimi-k3" to PriceEntry(3.0, 15.0, 0.3),
        "kimi-k2" to PriceEntry(0.6, 2.5, 0.15),
    )

    /**
     * Resolve the price entry for a model id. Handles:
     *  1. exact match ("gpt-4o");
     *  2. OpenRouter vendor-prefixed ids ("openai/gpt-4o" → "gpt-4o",
     *     "anthropic/claude-sonnet-4" → "claude-sonnet-4");
     *  3. case-insensitive fallback.
     * Returns null when the model is unknown — callers must treat null as
     * "cost unknown", never as zero.
     */
    fun priceFor(modelId: String): PriceEntry? {
        if (modelId.isBlank()) return null
        entries[modelId]?.let { return it }
        val slash = modelId.lastIndexOf('/')
        if (slash >= 0 && slash < modelId.length - 1) {
            entries[modelId.substring(slash + 1)]?.let { return it }
        }
        val lower = modelId.lowercase()
        for ((k, v) in entries) {
            if (k.equals(lower, ignoreCase = true)) return v
        }
        return null
    }
}
