package com.openminis.app.knowledgebase

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A knowledge base: a named collection of documents that the agent can
 * search and retrieve from. Each KB has its own embedding configuration.
 */
@Entity(tableName = "knowledge_bases")
data class KnowledgeBaseEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "embedding_provider") val embeddingProvider: String = "bm25",
    @ColumnInfo(name = "embedding_model") val embeddingModel: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "document_count") val documentCount: Int = 0,
    @ColumnInfo(name = "chunk_count") val chunkCount: Int = 0,
)