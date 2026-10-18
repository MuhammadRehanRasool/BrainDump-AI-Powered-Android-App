package dev.braindump.splitter.data.repository

import dev.braindump.splitter.data.ai.AICoreManager
import dev.braindump.splitter.data.ai.AiSettings
import dev.braindump.splitter.data.ai.SplitException
import dev.braindump.splitter.data.local.IdeaDao
import dev.braindump.splitter.data.local.IdeaEntity
import dev.braindump.splitter.data.local.toDomain
import dev.braindump.splitter.domain.model.AiStatus
import dev.braindump.splitter.domain.model.CaptureOutcome
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.domain.model.IdeaCategory
import dev.braindump.splitter.domain.model.IdeaStatus
import dev.braindump.splitter.domain.repository.IdeaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdeaRepositoryImpl @Inject constructor(
    private val dao: IdeaDao,
    private val ai: AICoreManager,
    private val settings: AiSettings,
) : IdeaRepository {

    // One drain at a time; a caller that waits here sees everything queued before it.
    private val drainMutex = Mutex()

    override val aiStatus: StateFlow<AiStatus> get() = ai.status

    override fun observeIdeas(): Flow<List<Idea>> =
        dao.observeAll().map { entities -> entities.map(IdeaEntity::toDomain) }

    override suspend fun captureAndSplit(rawInput: String): CaptureOutcome {
        val text = rawInput.trim()
        if (text.isEmpty()) return CaptureOutcome.Empty
        val now = System.currentTimeMillis()
        val id = dao.insert(IdeaEntity(rawInput = text, createdAt = now, updatedAt = now))

        splitPending()

        val idea = dao.getById(id) ?: return CaptureOutcome.Empty
        return when (idea.status) {
            IdeaStatus.READY -> CaptureOutcome.Split(idea.ideaTitle.orEmpty())
            IdeaStatus.PENDING -> CaptureOutcome.Queued(idea.lastError)
            IdeaStatus.FAILED -> CaptureOutcome.Failed(idea.lastError)
        }
    }

    override suspend fun toggleTask(ideaId: Long, taskIndex: Int) {
        if (taskIndex !in 0 until Int.SIZE_BITS - 1) return
        dao.toggleTaskBit(ideaId, 1 shl taskIndex, System.currentTimeMillis())
    }

    override suspend fun retry(ideaId: Long) {
        if (dao.resetFailed(ideaId, System.currentTimeMillis()) > 0) splitPending()
    }

    override suspend fun setCategory(ideaId: Long, category: IdeaCategory) {
        dao.setCategory(ideaId, category.name, System.currentTimeMillis())
    }

    override suspend fun delete(ideaId: Long) {
        dao.deleteById(ideaId)
    }

    override suspend fun warmUpAi() {
        ai.warmUp()
    }

    override val usePreviewModel: StateFlow<Boolean> get() = settings.usePreviewModel

    override suspend fun setUsePreviewModel(enabled: Boolean) {
        if (settings.usePreviewModel.value == enabled) return
        settings.setUsePreviewModel(enabled)
        ai.resetModel()
        ai.warmUp()
        splitPending()
    }

    override suspend fun splitPending() {
        drainMutex.withLock { drain() }
    }

    private suspend fun drain() {
        val handled = HashSet<Long>()

        // Re-query after each pass so ideas captured mid-run are picked up too.
        while (true) {
            val batch = dao.pendingIds().filterNot { it in handled }
            if (batch.isEmpty()) return

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
                            category = breakdown.category.name,
                            completedMask = 0,
                            status = IdeaStatus.READY,
                            lastError = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                    )
                } catch (e: SplitException.Deferred) {
                    // Every other queued idea would hit the same wall; stop and wait for the next foreground.
                    dao.update(idea.copy(lastError = e.message, updatedAt = System.currentTimeMillis()))
                    return
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
                }
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
