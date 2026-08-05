package com.openminis.app.knowledgebase

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A document inside a knowledge base. Represents a single file or
 * text entry that has been ingested and chunked.
 */
@Entity(
    tableName = "kb_documents",
    foreignKeys = [
        ForeignKey(
            entity = KnowledgeBaseEntity::class,
            parentColumns = ["id"],
            childColumns = ["kb_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["kb_id"])]
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "kb_id") val kbId: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "source_path") val sourcePath: String = "",
    @ColumnInfo(name = "source_type") val sourceType: String = "text",
    @ColumnInfo(name = "mime_type") val mimeType: String = "text/plain",
    @ColumnInfo(name = "file_size") val fileSize: Long = 0,
    @ColumnInfo(name = "chunk_count") val chunkCount: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "checksum") val checksum: String = "",
)