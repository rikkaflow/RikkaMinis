package com.openminis.app.provider

import org.json.JSONObject

/**
 * [T-cost-catalog] Per-model price catalog (USD per 1M tokens).
 *
 * Data lives in `assets/model_prices.json` (NOT hardcoded in Kotlin) so prices
 * can be updated by swapping one data file — no code change, no recompilation
 * of logic. The schema is:
 *   { "<modelId>": { "input": 2.5, "output": 10.0, "cacheRead": 1.25, "cacheWrite": 12.5 } }
 * input/output required; cacheRead/cacheWrite optional (absent = provider
 * charges normal input price / has no cache-write pricing).
 *
 * Source of the bundled snapshot: LiteLLM's community-maintained
 * `model_prices_and_context_window.json` (MIT, BerriAI/litellm), trimmed to the
 * models this app surfaces. Prices drift over time — this is an ESTIMATE for
 * display, not billing. User-supplied overrides (ModelOverrides.*PricePerMillion)
 * always win over this table (see CostCalculator).
 *
 * Design rules (mirrors AgentExecutionBudget's "don't fabricate" discipline):
 *  - Unknown model → [PriceEntry] absent → cost shows as "unknown", never 0.
 *  - Lookup is fuzzy: exact id → vendor-prefix strip ("openai/gpt-4o" →
 *    "gpt-4o") → date-suffix strip ("deepseek-v4-pro-0813" → "deepseek-v4-pro")
 *    → case-insensitive.
 */
object ModelPriceCatalog {

    /**
     * @param inputPerMillion  USD per 1M fresh input tokens.
     * @param outputPerMillion USD per 1M output tokens.
     * @param cacheReadPerMillion USD per 1M cache-read input tokens (prompt cache hits).
     * @param cacheWritePerMillion USD per 1M cache-creation tokens (Anthropic cache writes).
     */
    data class PriceEntry(
        val inputPerMillion: Double,
        val outputPerMillion: Double,
        val cacheReadPerMillion: Double? = null,
        val cacheWritePerMillion: Double? = null,
    )

    private var cachedEntries: Map<String, PriceEntry>? = null

    private val entries: Map<String, PriceEntry>
        get() = cachedEntries ?: parseJson(loader()).also { cachedEntries = it }

    /**
     * Raw JSON source. Production sets this at app startup (MinisApp.onCreate);
     * the default returns "" so an un-wired process degrades to an empty
     * catalog (cost → "unknown") rather than crashing. Tests inject an
     * in-memory string. Keeping this Android-free makes the object JVM-testable.
     */
    var loader: () -> String = { "" }

    /** Reload from [loader] (tests / hypothetical future remote refresh). */
    fun reload() {
        cachedEntries = null
    }

    /** Trailing date suffix: "-" followed by 4–8 digits, anchored at the end. */
    private val DATE_SUFFIX_REGEX = Regex("""-\d{4,8}$""")

    /** Parse the catalog JSON into entries. Malformed entries are skipped. */
    fun parseJson(json: String): Map<String, PriceEntry> {
        val out = HashMap<String, PriceEntry>()
        val root = try { JSONObject(json) } catch (_: Exception) { return out }
        val keys = root.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val e = root.optJSONObject(id) ?: continue
            val input = e.optDouble("input", Double.NaN)
            val output = e.optDouble("output", Double.NaN)
            if (input.isNaN() || output.isNaN()) continue
            val cacheRead = e.optDouble("cacheRead", Double.NaN).takeUnless { it.isNaN() }
            val cacheWrite = e.optDouble("cacheWrite", Double.NaN).takeUnless { it.isNaN() }
            out[id] = PriceEntry(input, output, cacheRead, cacheWrite)
        }
        return out
    }

    /** Number of loaded entries (diagnostics/tests). */
    fun size(): Int = entries.size

    /**
     * Resolve the price entry for a model id. Handles:
     *  1. exact match ("gpt-4o");
     *  2. vendor-prefixed ids ("openai/gpt-4o" → "gpt-4o");
     *  3. date-suffix strip ("deepseek-v4-pro-0813" → "deepseek-v4-pro");
     *  4. case-insensitive fallback.
     * Returns null when the model is unknown — callers must treat null as
     * "cost unknown", never as zero.
     */
    fun priceFor(modelId: String): PriceEntry? = priceForFrom(entries, modelId)

    /**
     * Pure fuzzy-lookup against an explicit [map] — production [priceFor] feeds
     * the asset-backed map; tests inject a small parsed map. Rules identical.
     */
    fun priceForFrom(map: Map<String, PriceEntry>, modelId: String): PriceEntry? {
        if (modelId.isBlank()) return null
        map[modelId]?.let { return it }
        val slash = modelId.lastIndexOf('/')
        if (slash >= 0 && slash < modelId.length - 1) {
            map[modelId.substring(slash + 1)]?.let { return it }
        }
        val dateStrip = DATE_SUFFIX_REGEX.find(modelId)?.value
        if (dateStrip != null) {
            map[modelId.removeSuffix(dateStrip)]?.let { return it }
            if (slash >= 0) {
                map[modelId.substring(slash + 1).removeSuffix(dateStrip)]?.let { return it }
            }
        }
        val lower = modelId.lowercase()
        for ((k, v) in map) {
            if (k.equals(lower, ignoreCase = true)) return v
        }
        return null
    }
}
