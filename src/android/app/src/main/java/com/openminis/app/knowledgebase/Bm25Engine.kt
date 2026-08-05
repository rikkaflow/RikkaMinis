package com.openminis.app.knowledgebase

import kotlin.math.ln

/**
 * BM25 scoring engine — the classic probabilistic retrieval model used by
 * Lucene/Elasticsearch. Works entirely on-device, no model download, no
 * network. Scores chunks by term frequency normalized against document
 * length, with inverse document frequency weighting for rarity.
 *
 * For v1 this is the DEFAULT embedding provider ("bm25") so a knowledge
 * base works out of the box with zero configuration. API-based semantic
 * embeddings can be added later as a second provider.
 */
class Bm25Engine {

    private val k1 = 1.2f
    private val b = 0.75f

    data class ScoredChunk(
        val chunk: ChunkEntity,
        val score: Float,
    )

    /** Tokenize a query/text into normalized terms. */
    fun tokenize(text: String): List<String> {
        val terms = mutableListOf<String>()
        val tokenBuilder = StringBuilder()
        val lower = text.lowercase()

        fun flush() {
            if (tokenBuilder.isNotEmpty()) {
                // Minimal stemming: drop common suffixes
                var t = tokenBuilder.toString()
                if (t.length > 4) {
                    when {
                        t.endsWith("ing") && t.length > 5 -> t = t.dropLast(3)
                        t.endsWith("ed") && t.length > 4 -> t = t.dropLast(2)
                        t.endsWith("es") && t.length > 4 -> t = t.dropLast(2)
                        t.endsWith("s") && !t.endsWith("ss") -> t = t.dropLast(1)
                    }
                }
                if (t.length >= 2) terms.add(t)
                tokenBuilder.clear()
            }
        }

        for (c in lower) {
            if (c.isLetterOrDigit() || c.code in 0x4E00..0x9FFF) {
                tokenBuilder.append(c)
            } else {
                flush()
            }
        }
        flush()
        return terms
    }

    /**
     * Score chunks against a query using BM25.
     * Uses in-memory IDF computed from the chunk collection itself,
     * so it degrades gracefully when the corpus is small.
     */
    fun score(
        chunks: List<ChunkEntity>,
        query: String,
        topK: Int = 8,
    ): List<ScoredChunk> {
        if (chunks.isEmpty() || query.isBlank()) return emptyList()

        val queryTerms = tokenize(query).distinct()
        if (queryTerms.isEmpty()) return emptyList()

        val n = chunks.size
        val avgLen = chunks.map { it.content.length }.average().toFloat().coerceAtLeast(1f)

        // Document frequency: how many chunks contain each term
        val df = HashMap<String, Int>()
        val termFreqs = HashMap<String, HashMap<String, Int>>() // chunkId -> term -> freq
        for (chunk in chunks) {
            val tokens = tokenize(chunk.content)
            if (tokens.isEmpty()) continue
            val freq = HashMap<String, Int>()
            for (t in tokens) {
                freq[t] = (freq[t] ?: 0) + 1
            }
            for (t in freq.keys) {
                df[t] = (df[t] ?: 0) + 1
            }
            termFreqs[chunk.id] = freq
        }

        val results = mutableListOf<ScoredChunk>()
        for (chunk in chunks) {
            val freq = termFreqs[chunk.id] ?: continue
            val docLen = chunk.content.length.toFloat().coerceAtLeast(1f)
            var score = 0f
            for (term in queryTerms) {
                val tf = freq[term] ?: 0
                if (tf == 0) continue
                val nq = df[term] ?: 1
                val idf = ln(((n - nq + 0.5f) / (nq + 0.5f)) + 1f)
                val denom = tf + k1 * (1 - b + b * docLen / avgLen)
                score += idf * (tf * (k1 + 1)) / denom
            }
            if (score > 0) {
                results.add(ScoredChunk(chunk, score))
            }
        }

        return results
            .sortedByDescending { it.score }
            .take(topK)
    }
}