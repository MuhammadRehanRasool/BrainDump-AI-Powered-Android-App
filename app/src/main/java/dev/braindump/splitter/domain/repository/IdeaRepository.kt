package dev.braindump.splitter.domain.repository

import dev.braindump.splitter.domain.model.AiStatus
import dev.braindump.splitter.domain.model.CaptureOutcome
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.domain.model.IdeaCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface IdeaRepository {
    val aiStatus: StateFlow<AiStatus>

    /** All ideas, newest first. */
    fun observeIdeas(): Flow<List<Idea>>

    /**
     * Persists the raw dump, then splits it right away while the app is in the foreground.
     * Gemini Nano refuses background requests, so anything left over stays queued for [splitPending].
     */
    suspend fun captureAndSplit(rawInput: String): CaptureOutcome

    suspend fun toggleTask(ideaId: Long, taskIndex: Int)

    suspend fun retry(ideaId: Long)

    /** Overrides the category Gemini Nano picked. */
    suspend fun setCategory(ideaId: Long, category: IdeaCategory)

    suspend fun delete(ideaId: Long)

    /** Splits everything still queued. Call while the app is in the foreground. */
    suspend fun splitPending()

    /** Checks model availability, downloads it if needed and warms it up. */
    suspend fun warmUpAi()

    /** Whether to try the AICore Developer Preview build of Gemini Nano first. */
    val usePreviewModel: StateFlow<Boolean>

    /** Saves the choice, re-selects the model and splits anything that was waiting. */
    suspend fun setUsePreviewModel(enabled: Boolean)
}
