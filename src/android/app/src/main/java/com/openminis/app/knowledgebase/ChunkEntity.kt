package com.openminis.app.knowledgebase

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A text chunk of a document, with optional embedding vector.
 * The embedding is stored as a JSON float array for BM25 mode,
 * or as a base64-encoded binary blob for API embeddings.
 */
@Entity(
    tableName = "kb_chunks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["document_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["document_id"]),
        Index(value = ["kb_id"]),
    ]
)
data class ChunkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "kb_id") val kbId: String,
    @ColumnInfo(name = "document_id") val documentId: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "token_count") val tokenCount: Int = 0,
    @ColumnInfo(name = "embedding_json") val embeddingJson: String? = null,
    @ColumnInfo(name = "embedding_provider") val embeddingProvider: String = "bm25",
    @ColumnInfo(name = "created_at") val createdAt: Long,
)