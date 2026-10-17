package dev.braindump.splitter.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [IdeaEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class BrainDumpDatabase : RoomDatabase() {
    abstract fun ideaDao(): IdeaDao

    companion object {
        const val NAME = "braindump.db"
    }
}
