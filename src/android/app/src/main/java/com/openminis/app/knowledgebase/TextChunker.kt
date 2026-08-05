package com.openminis.app.knowledgebase

/**
 * Splits text into overlapping chunks for embedding and retrieval.
 * Uses a recursive splitting strategy: paragraph → sentence → token.
 */
object TextChunker {

    private const val DEFAULT_CHUNK_SIZE = 800
    private const val DEFAULT_CHUNK_OVERLAP = 200
    private const val MIN_CHUNK_LENGTH = 50

    data class Chunk(
        val content: String,
        val index: Int,
        val tokenCount: Int,
    )

    /**
     * Split text into chunks. Tries paragraph boundaries first,
     * then sentence boundaries, then word boundaries.
     */
    fun chunkText(
        text: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        chunkOverlap: Int = DEFAULT_CHUNK_OVERLAP,
    ): List<Chunk> {
        if (text.isBlank()) return emptyList()
        if (text.length <= chunkSize) {
            return listOf(Chunk(text, 0, estimateTokens(text)))
        }

        val paragraphs = splitIntoParagraphs(text)
        return buildChunks(paragraphs, chunkSize, chunkOverlap)
    }

    private fun buildChunks(
        segments: List<String>,
        chunkSize: Int,
        chunkOverlap: Int,
    ): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var currentChunk = StringBuilder()
        var index = 0

        for (segment in segments) {
            if (currentChunk.length + segment.length <= chunkSize) {
                if (currentChunk.isNotEmpty()) currentChunk.append("\n\n")
                currentChunk.append(segment)
            } else {
                // Flush current chunk
                if (currentChunk.isNotEmpty()) {
                    chunks.add(Chunk(currentChunk.toString(), index++, estimateTokens(currentChunk.toString())))
                }
                // Start new chunk — if segment is too long, split it further
                if (segment.length > chunkSize) {
                    val subChunks = splitLongSegment(segment, chunkSize, chunkOverlap)
                    for (sc in subChunks) {
                        chunks.add(Chunk(sc, index++, estimateTokens(sc)))
                    }
                    currentChunk = StringBuilder()
                } else {
                    currentChunk = StringBuilder(segment)
                }
            }
        }

        // Flush remaining
        if (currentChunk.isNotEmpty() && currentChunk.length >= MIN_CHUNK_LENGTH) {
            chunks.add(Chunk(currentChunk.toString(), index, estimateTokens(currentChunk.toString())))
        }

        return chunks
    }

    private fun splitLongSegment(text: String, chunkSize: Int, overlap: Int): List<String> {
        val result = mutableListOf<String>()
        val sentences = splitIntoSentences(text)
        var start = 0
        while (start < sentences.size) {
            val chunk = buildList {
                var len = 0
                var i = start
                while (i < sentences.size && len + sentences[i].length <= chunkSize) {
                    add(sentences[i])
                    len += sentences[i].length
                    i++
                }
                if (isEmpty()) {
                    // Single sentence is longer than chunkSize — hard split
                    add(sentences[start].substring(0, chunkSize))
                }
            }
            result.add(chunk.joinToString(""))

            // Advance with overlap
            val advance = maxOf(1, chunk.size - (overlap * chunk.size / chunkSize))
            start += advance
        }
        return result
    }

    private fun splitIntoParagraphs(text: String): List<String> {
        return text.split(Regex("\n\\s*\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun splitIntoSentences(text: String): List<String> {
        return text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    /**
     * Rough token estimation: ~4 chars per token for English,
     * ~2 chars per token for CJK characters.
     */
    fun estimateTokens(text: String): Int {
        var cjkCount = 0
        var asciiCount = 0
        for (c in text) {
            when {
                c.code in 0x4E00..0x9FFF || c.code in 0x3000..0x303F || c.code in 0xFF00..0xFFEF -> cjkCount++
                c.code in 0x20..0x7E || c == '\n' || c == '\t' -> asciiCount++
            }
        }
        return (cjkCount / 2) + (asciiCount / 4) + 1
    }
}