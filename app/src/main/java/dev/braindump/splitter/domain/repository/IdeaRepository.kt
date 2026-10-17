package dev.braindump.splitter.domain.repository

import dev.braindump.splitter.domain.model.AiStatus
import dev.braindump.splitter.domain.model.Idea
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IdeaRepository {
    val aiStatus: StateFlow<AiStatus>

    /** All ideas, newest first. */
    fun observeIdeas(): Flow<List<Idea>>

    /** Persists the raw dump immediately and schedules on-device splitting. */
    suspend fun capture(rawInput: String)

    suspend fun toggleTask(ideaId: Long, taskIndex: Int)

    suspend fun retry(ideaId: Long)

    suspend fun delete(ideaId: Long)

    /** Re-kicks splitting for anything still queued, e.g. when the app returns to the foreground. */
    suspend fun resumePendingWork()

    /** Binds AICore and starts the model download early so the first split is fast. */
    suspend fun warmUpAi()

    /** Splits every queued idea. Called from the background worker only. */
    suspend fun processPending(): ProcessResult
}

enum class ProcessResult { DONE, RETRY_LATER }
