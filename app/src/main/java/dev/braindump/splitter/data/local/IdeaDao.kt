package dev.braindump.splitter.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface IdeaDao {

    @Query("SELECT * FROM ideas ORDER BY created_at DESC, id DESC")
    fun observeAll(): Flow<List<IdeaEntity>>

    @Query("SELECT * FROM ideas WHERE id = :id")
    suspend fun getById(id: Long): IdeaEntity?

    @Query("SELECT id FROM ideas WHERE status = 'PENDING' ORDER BY created_at ASC, id ASC")
    suspend fun pendingIds(): List<Long>

    @Query("SELECT COUNT(*) FROM ideas WHERE status = 'PENDING'")
    suspend fun countPending(): Int

    @Insert
    suspend fun insert(idea: IdeaEntity): Long

    @Update
    suspend fun update(idea: IdeaEntity)

    /** SQLite has no XOR operator: (a | b) - (a & b) flips the bit atomically. */
    @Query(
        "UPDATE ideas SET completed_mask = (completed_mask | :bit) - (completed_mask & :bit), " +
            "updated_at = :now WHERE id = :id AND status = 'READY'"
    )
    suspend fun toggleTaskBit(id: Long, bit: Int, now: Long)

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
