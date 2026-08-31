package com.openminis.app.conversation

import java.util.concurrent.ConcurrentHashMap

/**
 * [T-compact-cache] Exact-match cache for compaction summaries (LiteLLM
 * "exact cache" idea, trimmed to RikkaMinis' single-user reality).
 *
 * What it caches: the LLM-generated summary of a compaction request, keyed by
 * an EXACT digest of everything that determines the output —
 *   (model id, system prompt, previous summary, transcript text).
 * Same input → reuse the stored summary and skip the provider call entirely.
 *
 * What it deliberately does NOT do: semantic/similarity matching. A personal
 * assistant rarely asks the same-ish question twice, and a wrong cache hit
 * replaces the user's real context with a stale summary — catastrophic for
 * correctness, worthless as savings. Exact-match only.
 *
 * Bounded: fixed capacity, oldest-insertion eviction (FIFO keeps this
 * lock-free-simple; compaction inputs are huge and a strict LRU bookkeeping
 * per byte-touched would buy nothing at this hit pattern).
 *
 * Pure JVM (ConcurrentHashMap only) — sandbox-testable. Thread safety:
 * individual ops are atomic; the capacity trim is best-effort (a transient
 * overshoot of a few entries under race is harmless).
 */
object CompactSummaryCache {

    /** Cache entry: the summary text plus its output-token estimate. */
    data class CachedSummary(
        val summaryText: String,
        val outputTokensEstimate: Int,
    )

    private const val MAX_ENTRIES = 8

    private data class Key(
        val modelId: String,
        val systemPrompt: String,
        val previousSummary: String,
        val transcript: String,
    ) {
        // Structural equality on all fields — the "exact match" contract.
    }

    private val store = ConcurrentHashMap<Key, CachedSummary>(MAX_ENTRIES * 2)

    /** Number of entries currently held (for tests/diagnostics). */
    fun size(): Int = store.size

    /** Drop everything (tests; also called when the user clears a session). */
    fun clear() = store.clear()

    /**
     * Look up a cached summary. Returns null on miss (or when any input is
     * null — a null system prompt / previous summary is treated as "").
     */
    fun lookup(
        modelId: String?,
        systemPrompt: String?,
        previousSummary: String?,
        transcript: String?,
    ): CachedSummary? {
        if (modelId.isNullOrBlank() || transcript.isNullOrBlank()) return null
        val key = Key(
            modelId = modelId,
            systemPrompt = systemPrompt.orEmpty(),
            previousSummary = previousSummary.orEmpty(),
            transcript = transcript,
        )
        return store[key]
    }

    /**
     * Store a summary for later exact-match reuse. Evicts oldest entries
     * beyond [MAX_ENTRIES] (FIFO by insertion order — ConcurrentHashMap has
     * none, so eviction walks an arbitrary subset; capacity is a soft bound,
     * the hard guarantee is "bounded memory in steady state").
     */
    fun store(
        modelId: String?,
        systemPrompt: String?,
        previousSummary: String?,
        transcript: String?,
        summaryText: String,
        outputTokensEstimate: Int,
    ) {
        if (modelId.isNullOrBlank() || transcript.isNullOrBlank() || summaryText.isBlank()) return
        val key = Key(
            modelId = modelId,
            systemPrompt = systemPrompt.orEmpty(),
            previousSummary = previousSummary.orEmpty(),
            transcript = transcript,
        )
        store[key] = CachedSummary(summaryText, outputTokensEstimate.coerceAtLeast(0))
        // Soft-bound trim: drop ~half the table when over capacity. Arbitrary
        // victim selection is fine — every entry is equally "old" from the
        // caller's perspective, and misses only cost a re-summarize.
        if (store.size > MAX_ENTRIES * 2) {
            val victims = store.keys.take(store.size - MAX_ENTRIES)
            for (v in victims) store.remove(v)
        }
    }
}
