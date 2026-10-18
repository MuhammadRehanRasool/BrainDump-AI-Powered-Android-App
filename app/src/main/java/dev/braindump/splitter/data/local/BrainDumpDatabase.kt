package dev.braindump.splitter.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [IdeaEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class BrainDumpDatabase : RoomDatabase() {
    abstract fun ideaDao(): IdeaDao

    companion object {
        const val NAME = "braindump.db"

        /** Adds the category column; existing ideas keep everything and show as Other. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ideas ADD COLUMN category TEXT")
            }
        }
    }
}
