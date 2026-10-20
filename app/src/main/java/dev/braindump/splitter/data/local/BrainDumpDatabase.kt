package dev.braindump.splitter.data.local

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Database(entities = [IdeaEntity::class], version = 4, exportSchema = false)
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

        /** Adds the refine chat log; existing ideas start with no turns. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ideas ADD COLUMN refinements TEXT")
            }
        }

        /**
         * Ideas became Markdown notes: the summary, task list and tags of each split idea are
         * folded into one body (ticked tasks stay ticked as "- [x]"), and their columns dropped.
         * SQLite can't drop columns on older Androids, so the table is rebuilt.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE ideas_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, raw_input TEXT NOT NULL, idea_title TEXT, " +
                        "body TEXT, category TEXT, status TEXT NOT NULL, attempts INTEGER NOT NULL, last_error TEXT, " +
                        "created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, refinements TEXT)"
                )
                db.query(
                    "SELECT id, raw_input, idea_title, raw_concept, tech_stack_anchors, immediate_micro_tasks, " +
                        "category, completed_mask, status, attempts, last_error, created_at, updated_at, refinements FROM ideas"
                ).use { row ->
                    while (row.moveToNext()) {
                        val body = legacyBody(
                            concept = row.getString(3).orEmpty(),
                            tags = legacyList(row.getString(4)),
                            tasks = legacyList(row.getString(5)),
                            doneMask = row.getInt(7),
                        )
                        val values = ContentValues().apply {
                            put("id", row.getLong(0))
                            put("raw_input", row.getString(1))
                            put("idea_title", row.getString(2))
                            put("body", body.ifEmpty { null })
                            put("category", row.getString(6))
                            put("status", row.getString(8))
                            put("attempts", row.getInt(9))
                            put("last_error", row.getString(10))
                            put("created_at", row.getLong(11))
                            put("updated_at", row.getLong(12))
                            put("refinements", row.getString(13))
                        }
                        db.insert("ideas_new", SQLiteDatabase.CONFLICT_NONE, values)
                    }
                }
                db.execSQL("DROP TABLE ideas")
                db.execSQL("ALTER TABLE ideas_new RENAME TO ideas")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ideas_created_at ON ideas (created_at)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ideas_status ON ideas (status)")
            }
        }

        /** The JSON string lists version 3 stored tags and tasks in. */
        private fun legacyList(value: String?): List<String> =
            if (value.isNullOrEmpty()) emptyList()
            else runCatching { Json.decodeFromString(ListSerializer(String.serializer()), value) }.getOrDefault(emptyList())

        private fun legacyBody(concept: String, tags: List<String>, tasks: List<String>, doneMask: Int): String =
            buildList {
                if (concept.isNotBlank()) add(concept.trim())
                if (tasks.isNotEmpty()) {
                    add(
                        tasks.mapIndexed { index, task ->
                            val done = index < Int.SIZE_BITS - 1 && (doneMask and (1 shl index)) != 0
                            "- [${if (done) "x" else " "}] $task"
                        }.joinToString("\n")
                    )
                }
                if (tags.isNotEmpty()) add("*${tags.joinToString(" · ")}*")
            }.joinToString("\n\n")
    }
}
