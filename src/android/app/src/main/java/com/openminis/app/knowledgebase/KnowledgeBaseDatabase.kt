package com.openminis.app.knowledgebase

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        KnowledgeBaseEntity::class,
        DocumentEntity::class,
        ChunkEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class KnowledgeBaseDatabase : RoomDatabase() {
    abstract fun knowledgeBaseDao(): KnowledgeBaseDao

    companion object {
        @Volatile
        private var INSTANCE: KnowledgeBaseDatabase? = null

        fun getInstance(context: Context): KnowledgeBaseDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    KnowledgeBaseDatabase::class.java,
                    "minis_knowledge_base.db"
                )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}