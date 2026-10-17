package dev.braindump.splitter.data.repository

import dev.braindump.splitter.data.ai.AICoreManager
import dev.braindump.splitter.data.ai.SplitException
import dev.braindump.splitter.data.local.IdeaDao
import dev.braindump.splitter.data.local.IdeaEntity
import dev.braindump.splitter.data.local.toDomain
import dev.braindump.splitter.domain.model.AiStatus
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.domain.model.IdeaStatus
import dev.braindump.splitter.domain.repository.IdeaRepository
import dev.braindump.splitter.domain.repository.ProcessResult
import dev.braindump.splitter.work.SplitScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdeaRepositoryImpl @Inject constructor(
    private val dao: IdeaDao,
    private val ai: AICoreManager,
    private val scheduler: SplitScheduler,
) : IdeaRepository {

    override val aiStatus: StateFlow<AiStatus> get() = ai.status

    override fun observeIdeas(): Flow<List<Idea>> =
        dao.observeAll().map { entities -> entities.map(IdeaEntity::toDomain) }

    override suspend fun capture(rawInput: String) {
        val text = rawInput.trim()
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        dao.insert(IdeaEntity(rawInput = text, createdAt = now, updatedAt = now))
        scheduler.enqueue()
    }

    override suspend fun toggleTask(ideaId: Long, taskIndex: Int) {
        if (taskIndex !in 0 until Int.SIZE_BITS - 1) return
        dao.toggleTaskBit(ideaId, 1 shl taskIndex, System.currentTimeMillis())
    }

    override suspend fun retry(ideaId: Long) {
        if (dao.resetFailed(ideaId, System.currentTimeMillis()) > 0) scheduler.enqueue()
    }

    override suspend fun delete(ideaId: Long) {
        dao.deleteById(ideaId)
    }

    override suspend fun resumePendingWork() {
        if (dao.countPending() > 0) scheduler.enqueue()
    }

    override suspend fun warmUpAi() {
        ai.warmUp()
    }

    override suspend fun processPending(): ProcessResult {
        val handled = HashSet<Long>()
        var retryLater = false

        // Re-query after each pass so ideas captured mid-run are picked up too.
        while (true) {
            val batch = dao.pendingIds().filterNot { it in handled }
            if (batch.isEmpty()) break

            for (id in batch) {
                handled += id
                val idea = dao.getById(id) ?: continue
                if (idea.status != IdeaStatus.PENDING) continue

                try {
                    val breakdown = ai.splitIdea(idea.rawInput)
                    dao.update(
                        idea.copy(
                            ideaTitle = breakdown.ideaTitle,
                            rawConcept = breakdown.rawConcept,
                            techStackAnchors = breakdown.techStackAnchors,
                            immediateMicroTasks = breakdown.immediateMicroTasks,
                            completedMask = 0,
                            status = IdeaStatus.READY,
                            attempts = idea.attempts + 1,
                            lastError = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                } catch (e: SplitException) {
                    val attempts = idea.attempts + 1
                    val giveUp = !e.retryable || attempts >= MAX_ATTEMPTS
                    dao.update(
                        idea.copy(
                            status = if (giveUp) IdeaStatus.FAILED else IdeaStatus.PENDING,
                            attempts = attempts,
                            lastError = e.message,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                    if (!giveUp) retryLater = true
                }
            }
        }
        return if (retryLater) ProcessResult.RETRY_LATER else ProcessResult.DONE
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
