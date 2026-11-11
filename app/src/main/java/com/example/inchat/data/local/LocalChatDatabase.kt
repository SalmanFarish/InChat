package com.example.inchat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [CachedMessageEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LocalChatDatabase : RoomDatabase() {

    abstract fun messageDao(): LocalChatDao

    companion object {
        @Volatile
        private var INSTANCE: LocalChatDatabase? = null

        fun getInstance(context: Context): LocalChatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalChatDatabase::class.java,
                    "inchat-local.db"
                ).build().also { database ->
                    INSTANCE = database
                }
            }
        }
    }
}