package dev.braindump.splitter.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.ColumnInfo
import kotlinx.coroutines.flow.Flow

data class IdentityStyleSample(
    val category: String?,
    @ColumnInfo(name = "idea_title")
    val ideaTitle: String?,
    val body: String,
)

@Dao
interface IdeaDao {

    @Query("SELECT * FROM ideas ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<IdeaEntity>>

    @Query("SELECT * FROM ideas WHERE id = :id")
    suspend fun getById(id: Long): IdeaEntity?

    @Query("SELECT id FROM ideas WHERE status = 'PENDING' ORDER BY created_at ASC, id ASC")
    suspend fun pendingIds(): List<Long>

    @Query(
        "SELECT category, idea_title, body FROM ideas WHERE status = 'READY' " +
            "AND body IS NOT NULL AND TRIM(body) != '' " +
            "AND (:excludeId IS NULL OR id != :excludeId) " +
            "ORDER BY updated_at DESC, id DESC LIMIT :limit"
    )
    suspend fun recentStyleSamples(limit: Int, excludeId: Long? = null): List<IdentityStyleSample>

    @Insert
    suspend fun insert(idea: IdeaEntity): Long

    @Update
    suspend fun update(idea: IdeaEntity)

    @Query(
        "UPDATE ideas SET status = 'PENDING', attempts = 0, last_error = NULL, updated_at = :now " +
            "WHERE id = :id AND status = 'FAILED'"
    )
    suspend fun resetFailed(id: Long, now: Long): Int

    @Query("UPDATE ideas SET category = :category, updated_at = :now WHERE id = :id")
    suspend fun setCategory(id: Long, category: String, now: Long)

    @Query("DELETE FROM ideas WHERE id = :id")
    suspend fun deleteById(id: Long)
}
