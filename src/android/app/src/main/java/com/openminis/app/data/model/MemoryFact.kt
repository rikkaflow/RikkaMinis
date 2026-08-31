package com.openminis.app.data.model

/**
 * [feat/memory-facts] A single structured fact extracted from a memory entry.
 *
 * Deliberately tiny and dependency-free: a fact is a subject-predicate-object
 * triple with a confidence and provenance. This mirrors the "entity /
 * preference" layer Mem0-style memory systems add on top of raw text logs —
 * retrieval returns facts first, raw entries serve as evidence.
 *
 * JSON on disk (facts.jsonl, one fact per line) uses snake_case keys:
 *   {"subject": "...", "predicate": "...", "object": "...", "confidence": 0.9,
 *    "source": "2026-08-31.md", "device_id": "...", "created_at": "2026-08-31T12:00:00"}
 *
 * [decision B] device_id + created_at are the forward-compat gate for a future
 * SyncMerge integration: the format is already sync-ready, only a semantic
 * merge-rsolver would be needed — the storage format never changes.
 */
data class MemoryFact(
    val subject: String,
    val predicate: String,
    val `object`: String,
    val confidence: Double = 0.8,
    val source: String = "",
    val deviceId: String = "unknown",
    val createdAt: String = "",
) {
    /** Dedup key: exact triple match. Same-day duplicates are collapsed on
     *  append; cross-day repeats are allowed (recency decay ranks the newer
     *  one higher — a natural confidence signal). */
    fun dedupKey(): String = "$subject\u0000$predicate\u0000$`object`"

    /** "2026-08-31" prefix of [createdAt] — used for same-day dedup and
     *  recency weighting. Empty/unparseable → null (no penalty, weight 1.0). */
    fun createdDatePrefix(): String? =
        createdAt.takeIf { it.length >= 10 }?.take(10)
}
