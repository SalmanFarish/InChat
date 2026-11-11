package com.example.inchat.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [CachedMessageEntity::class],
    version = 3,
    exportSchema = false
)
abstract class LocalChatDatabase : RoomDatabase() {

    abstract fun messageDao(): LocalChatDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE cached_messages ADD COLUMN isPending INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE cached_messages ADD COLUMN systemEvent TEXT NOT NULL DEFAULT ''"
                )
            }
        }

        @Volatile
        private var INSTANCE: LocalChatDatabase? = null

        fun getInstance(context: Context): LocalChatDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocalChatDatabase::class.java,
                    "inchat-local.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { database ->
                    INSTANCE = database
                }
            }
        }
    }
}