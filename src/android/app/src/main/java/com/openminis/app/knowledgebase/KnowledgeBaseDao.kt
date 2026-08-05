package com.openminis.app.knowledgebase

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface KnowledgeBaseDao {

    // -- Knowledge Bases --

    @Query("SELECT * FROM knowledge_bases ORDER BY updated_at DESC")
    fun getAllKnowledgeBases(): Flow<List<KnowledgeBaseEntity>>

    @Query("SELECT * FROM knowledge_bases ORDER BY updated_at DESC")
    suspend fun getAllKnowledgeBasesList(): List<KnowledgeBaseEntity>

    @Query("SELECT * FROM knowledge_bases WHERE id = :id")
    suspend fun getKnowledgeBase(id: String): KnowledgeBaseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertKnowledgeBase(kb: KnowledgeBaseEntity)

    @Query("DELETE FROM knowledge_bases WHERE id = :id")
    suspend fun deleteKnowledgeBase(id: String)

    @Query("UPDATE knowledge_bases SET document_count = :docCount, chunk_count = :chunkCount, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateKnowledgeBaseStats(id: String, docCount: Int, chunkCount: Int, updatedAt: Long)

    // -- Documents --

    @Query("SELECT * FROM kb_documents WHERE kb_id = :kbId ORDER BY created_at DESC")
    fun getDocumentsByKb(kbId: String): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM kb_documents WHERE kb_id = :kbId ORDER BY created_at DESC")
    suspend fun getDocumentsByKbList(kbId: String): List<DocumentEntity>

    @Query("SELECT * FROM kb_documents WHERE id = :id")
    suspend fun getDocument(id: String): DocumentEntity?

    @Query("SELECT * FROM kb_documents WHERE kb_id = :kbId AND source_path = :path LIMIT 1")
    suspend fun getDocumentByPath(kbId: String, path: String): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocument(doc: DocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDocuments(docs: List<DocumentEntity>)

    @Query("DELETE FROM kb_documents WHERE id = :id")
    suspend fun deleteDocument(id: String)

    @Query("DELETE FROM kb_documents WHERE kb_id = :kbId")
    suspend fun deleteDocumentsByKb(kbId: String)

    @Query("SELECT COUNT(*) FROM kb_documents WHERE kb_id = :kbId")
    suspend fun documentCount(kbId: String): Int

    // -- Chunks --

    @Query("SELECT * FROM kb_chunks WHERE document_id = :docId ORDER BY chunk_index ASC")
    suspend fun getChunksByDocument(docId: String): List<ChunkEntity>

    @Query("SELECT * FROM kb_chunks WHERE kb_id = :kbId ORDER BY chunk_index ASC")
    suspend fun getChunksByKb(kbId: String): List<ChunkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChunks(chunks: List<ChunkEntity>)

    @Query("DELETE FROM kb_chunks WHERE document_id = :docId")
    suspend fun deleteChunksByDocument(docId: String)

    @Query("DELETE FROM kb_chunks WHERE kb_id = :kbId")
    suspend fun deleteChunksByKb(kbId: String)

    @Query("SELECT COUNT(*) FROM kb_chunks WHERE kb_id = :kbId")
    suspend fun chunkCount(kbId: String): Int

    // -- BM25 keyword search (SQLite FTS5 or LIKE-based fallback) --

    @Query("""
        SELECT * FROM kb_chunks 
        WHERE kb_id = :kbId 
        AND content LIKE '%' || :query || '%' 
        ORDER BY chunk_index ASC 
        LIMIT :limit
    """)
    suspend fun searchChunksByKeyword(kbId: String, query: String, limit: Int = 10): List<ChunkEntity>

    @Query("""
        SELECT c.* FROM kb_chunks c
        INNER JOIN kb_documents d ON c.document_id = d.id
        WHERE c.kb_id = :kbId 
        AND d.title LIKE '%' || :query || '%'
        ORDER BY c.chunk_index ASC 
        LIMIT :limit
    """)
    suspend fun searchDocumentsByTitle(kbId: String, query: String, limit: Int = 10): List<ChunkEntity>

    /**
     * Multi-keyword BM25-style search: split query into words,
     * count matches per chunk, order by match density.
     * Returns chunks sorted by relevance (most keyword matches first).
     */
    @Query("""
        SELECT c.*, 
               (LENGTH(c.content) - LENGTH(REPLACE(LOWER(c.content), LOWER(:word1), ''))) / LENGTH(:word1) AS score
        FROM kb_chunks c
        WHERE c.kb_id = :kbId 
          AND LOWER(c.content) LIKE '%' || LOWER(:word1) || '%'
        ORDER BY score DESC
        LIMIT :limit
    """)
    suspend fun searchChunksByTerm(
        kbId: String,
        word1: String,
        limit: Int = 10,
    ): List<ChunkEntity>

    @Transaction
    suspend fun deleteDocumentAndChunks(docId: String) {
        deleteChunksByDocument(docId)
        deleteDocument(docId)
    }

    @Transaction
    suspend fun deleteKnowledgeBaseCascade(kbId: String) {
        deleteChunksByKb(kbId)
        deleteDocumentsByKb(kbId)
        deleteKnowledgeBase(kbId)
    }
}