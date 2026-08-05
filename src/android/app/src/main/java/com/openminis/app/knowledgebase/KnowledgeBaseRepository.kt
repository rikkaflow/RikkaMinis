package com.openminis.app.knowledgebase

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID

/**
 * KnowledgeBaseRepository — the single entry point for all knowledge base
 * operations: create/list/delete KBs, ingest documents (text, file paths,
 * raw content), chunk them, and run retrieval (BM25 for v1).
 *
 * v1 scope (offline-first):
 *  - KB CRUD
 *  - Document ingestion from text / sandbox file paths
 *  - Recursive chunking with overlap
 *  - BM25 keyword retrieval (no model download, no API key)
 *
 * Later (v2): API-based semantic embeddings (OpenAI-compatible /embedding),
 * document formats (PDF, EPUB), incremental re-ingest on file change.
 */
class KnowledgeBaseRepository(
    context: Context,
    private val dao: KnowledgeBaseDao = KnowledgeBaseDatabase.getInstance(context).knowledgeBaseDao(),
) {

    private val bm25 = Bm25Engine()

    // -- KB CRUD --

    fun getAllKnowledgeBases(): Flow<List<KnowledgeBaseEntity>> = dao.getAllKnowledgeBases()

    suspend fun getAllKnowledgeBasesList(): List<KnowledgeBaseEntity> = dao.getAllKnowledgeBasesList()

    suspend fun getKnowledgeBase(id: String): KnowledgeBaseEntity? = dao.getKnowledgeBase(id)

    suspend fun createKnowledgeBase(name: String, description: String = ""): KnowledgeBaseEntity {
        val now = System.currentTimeMillis()
        val kb = KnowledgeBaseEntity(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            description = description.trim(),
            createdAt = now,
            updatedAt = now,
        )
        dao.upsertKnowledgeBase(kb)
        return kb
    }

    suspend fun updateKnowledgeBase(kb: KnowledgeBaseEntity) {
        dao.upsertKnowledgeBase(kb.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteKnowledgeBase(id: String) {
        dao.deleteKnowledgeBaseCascade(id)
    }

    // -- Documents --

    fun getDocumentsByKb(kbId: String): Flow<List<DocumentEntity>> = dao.getDocumentsByKb(kbId)

    suspend fun getDocumentsByKbList(kbId: String): List<DocumentEntity> = dao.getDocumentsByKbList(kbId)

    suspend fun getDocument(id: String): DocumentEntity? = dao.getDocument(id)

    suspend fun deleteDocument(docId: String) {
        dao.deleteDocumentAndChunks(docId)
        refreshKbStats(dao.getDocument(docId)?.kbId ?: return)
    }

    // -- Ingestion --

    /**
     * Ingest raw text into a KB. Creates a document + chunks.
     */
    suspend fun ingestText(
        kbId: String,
        title: String,
        content: String,
        sourcePath: String = "",
        mimeType: String = "text/plain",
    ): DocumentEntity = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val checksum = sha256(content)

        // Skip if unchanged (idempotent re-ingest)
        val existing = if (sourcePath.isNotEmpty()) {
            dao.getDocumentByPath(kbId, sourcePath)
        } else null
        if (existing != null && existing.checksum == checksum) {
            return@withContext existing
        }

        val docId = existing?.id ?: UUID.randomUUID().toString()
        val chunks = TextChunker.chunkText(content)

        val doc = DocumentEntity(
            id = docId,
            kbId = kbId,
            title = title,
            sourcePath = sourcePath,
            mimeType = mimeType,
            fileSize = content.toByteArray(Charsets.UTF_8).size.toLong(),
            chunkCount = chunks.size,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            checksum = checksum,
        )

        // Replace old chunks for this document (full re-ingest)
        dao.deleteChunksByDocument(docId)
        dao.upsertDocument(doc)

        if (chunks.isNotEmpty()) {
            val chunkEntities = chunks.map { c ->
                ChunkEntity(
                    id = UUID.randomUUID().toString(),
                    kbId = kbId,
                    documentId = docId,
                    chunkIndex = c.index,
                    content = c.content,
                    contentHash = sha256(c.content),
                    tokenCount = c.tokenCount,
                    embeddingProvider = "bm25",
                    createdAt = now,
                )
            }
            dao.upsertChunks(chunkEntities)
        }

        refreshKbStats(kbId)
        dao.getDocument(docId) ?: doc
    }

    /**
     * Ingest a file from the sandbox filesystem. v1 supports text-ish
     * files read directly (the caller — e.g. the agent — passes content).
     * Binary formats (PDF/docx) are out of scope for v1.
     */
    suspend fun ingestFile(
        kbId: String,
        path: String,
        title: String = path.substringAfterLast('/'),
        mimeType: String = guessMimeType(path),
        content: String,
    ): DocumentEntity {
        return ingestText(kbId, title, content, sourcePath = path, mimeType = mimeType)
    }

    /**
     * Ingest a whole directory tree by walking it via a provided
     * file-content reader. Returns (ingested, skipped) counts.
     *
     * The reader lambda lets the caller supply content from the sandbox
     * (ShellExecutor) or from local storage — keeps this repo pure.
     */
    suspend fun ingestFiles(
        kbId: String,
        files: List<Pair<String, String>>, // (path, content)
    ): Pair<Int, Int> {
        var ingested = 0
        var skipped = 0
        for ((path, content) in files) {
            if (content.isBlank()) {
                skipped++
                continue
            }
            val title = path.substringAfterLast('/')
            val mime = guessMimeType(path)
            ingestText(kbId, title, content, sourcePath = path, mimeType = mime)
            ingested++
        }
        return ingested to skipped
    }

    // -- Retrieval --

    /**
     * Retrieve the top-k most relevant chunks for a query.
     * v1 uses BM25; later providers (semantic embeddings) plug in here.
     */
    suspend fun retrieve(
        kbId: String,
        query: String,
        topK: Int = 8,
        minScore: Float = 0f,
    ): List<RetrievedChunk> = withContext(Dispatchers.Default) {
        val chunks = dao.getChunksByKb(kbId)
        val scored = bm25.score(chunks, query, topK)
        val docIds = scored.map { it.chunk.documentId }.distinct()
        val docs = docIds.mapNotNull { dao.getDocument(it) }.associateBy { it.id }

        scored
            .filter { it.score >= minScore }
            .map { s ->
                RetrievedChunk(
                    chunk = s.chunk,
                    score = s.score,
                    document = docs[s.chunk.documentId],
                )
            }
    }

    /**
     * Retrieve across ALL knowledge bases (global search).
     */
    suspend fun retrieveAll(
        query: String,
        topK: Int = 8,
    ): List<RetrievedChunk> = withContext(Dispatchers.Default) {
        val kbs = dao.getAllKnowledgeBasesList()
        val results = mutableListOf<RetrievedChunk>()
        for (kb in kbs) {
            results.addAll(retrieve(kb.id, query, topK = topK / kbs.size.coerceAtLeast(1)))
        }
        results.sortedByDescending { it.score }.take(topK)
    }

    data class RetrievedChunk(
        val chunk: ChunkEntity,
        val score: Float,
        val document: DocumentEntity? = null,
    )

    // -- Internal --

    private suspend fun refreshKbStats(kbId: String) {
        val docCount = dao.documentCount(kbId)
        val chunkCount = dao.chunkCount(kbId)
        dao.updateKnowledgeBaseStats(kbId, docCount, chunkCount, System.currentTimeMillis())
    }

    private fun guessMimeType(path: String): String {
        return when (path.substringAfterLast('.').lowercase()) {
            "md", "markdown" -> "text/markdown"
            "txt" -> "text/plain"
            "json" -> "application/json"
            "yaml", "yml" -> "application/yaml"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "html", "htm" -> "text/html"
            "kt", "java", "py", "js", "ts", "c", "cpp", "h", "swift" -> "text/code"
            "sh", "bash", "zsh" -> "text/shell"
            else -> "text/plain"
        }
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}