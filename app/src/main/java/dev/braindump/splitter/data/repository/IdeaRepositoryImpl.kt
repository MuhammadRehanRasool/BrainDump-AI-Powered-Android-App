package dev.braindump.splitter.data.repository

import dev.braindump.splitter.data.ai.AICoreManager
import dev.braindump.splitter.data.ai.AiSettings
import dev.braindump.splitter.data.ai.IdeaBreakdown
import dev.braindump.splitter.data.ai.SplitException
import dev.braindump.splitter.data.local.IdeaDao
import dev.braindump.splitter.data.local.IdeaEntity
import dev.braindump.splitter.data.local.toDomain
import dev.braindump.splitter.data.local.withTurn
import dev.braindump.splitter.domain.model.AiStatus
import dev.braindump.splitter.domain.model.CaptureOutcome
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.domain.model.IdeaCategory
import dev.braindump.splitter.domain.model.IdeaStatus
import dev.braindump.splitter.domain.model.RefineOutcome
import dev.braindump.splitter.domain.model.RefineTurn
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

    override suspend fun refine(ideaId: Long, ask: String): RefineOutcome {
        val request = ask.trim()
        if (request.isEmpty()) return RefineOutcome.Failed("Say what to change")
        val idea = dao.getById(ideaId) ?: return RefineOutcome.Failed(DELETED)
        if (idea.status != IdeaStatus.READY) return RefineOutcome.Failed("Wait until the idea is split")

        val refined = try {
            ai.refineIdea(idea.toBreakdown(), idea.rawInput, request)
        } catch (e: SplitException) {
            return RefineOutcome.Failed(e.message ?: "Gemini Nano couldn't refine this")
        }

        // Re-read: tasks may have been ticked off while Gemini Nano was thinking.
        val latest = dao.getById(ideaId) ?: return RefineOutcome.Failed(DELETED)
        val summary = describeChanges(latest.toBreakdown(), refined)
        val now = System.currentTimeMillis()
        dao.update(
            latest.copy(
                ideaTitle = refined.ideaTitle,
                rawConcept = refined.rawConcept,
                techStackAnchors = refined.techStackAnchors,
                immediateMicroTasks = refined.immediateMicroTasks,
                category = refined.category.name,
                completedMask = carryOverDone(latest, refined.immediateMicroTasks),
                updatedAt = now,
            ).withTurn(RefineTurn(ask = request, reply = summary, at = now))
        )
        return RefineOutcome.Updated(summary)
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

    private fun IdeaEntity.toBreakdown() = IdeaBreakdown(
        ideaTitle = ideaTitle.orEmpty(),
        rawConcept = rawConcept.orEmpty(),
        techStackAnchors = techStackAnchors,
        immediateMicroTasks = immediateMicroTasks,
        category = IdeaCategory.parse(category),
    )

    /** A task keeps its tick when the refined list still has it, wherever it moved to. */
    private fun carryOverDone(idea: IdeaEntity, tasks: List<String>): Int {
        val done = idea.immediateMicroTasks
            .filterIndexed { index, _ -> (idea.completedMask and (1 shl index)) != 0 }
            .mapTo(HashSet()) { it.lowercase() }
        var mask = 0
        tasks.forEachIndexed { index, task -> if (task.lowercase() in done) mask = mask or (1 shl index) }
        return mask
    }

    /** The chat reply: what actually changed, read off the data rather than the model's word for it. */
    private fun describeChanges(before: IdeaBreakdown, after: IdeaBreakdown): String {
        val changes = ArrayList<String>()
        if (before.ideaTitle != after.ideaTitle) changes += "Renamed to “${after.ideaTitle}”"
        if (before.category != after.category) {
            changes += "Moved to ${after.category.name.lowercase().replaceFirstChar { it.titlecase() }}"
        }
        if (before.rawConcept != after.rawConcept) changes += "Updated the summary"

        val oldTasks = before.immediateMicroTasks.map { it.lowercase() }.toSet()
        val newTasks = after.immediateMicroTasks.map { it.lowercase() }.toSet()
        val added = after.immediateMicroTasks.filter { it.lowercase() !in oldTasks }
        val removed = before.immediateMicroTasks.filter { it.lowercase() !in newTasks }
        added.forEach { changes += "+ $it" }
        removed.forEach { changes += "− $it" }
        if (added.isEmpty() && removed.isEmpty() && before.immediateMicroTasks != after.immediateMicroTasks) {
            changes += "Reordered the tasks"
        }
        if (before.techStackAnchors != after.techStackAnchors) {
            changes += if (after.techStackAnchors.isEmpty()) "Cleared the tags" else "Tags: ${after.techStackAnchors.joinToString(", ")}"
        }
        return changes.joinToString("\n").ifEmpty { "Nothing changed. Try naming exactly what to change." }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        const val DELETED = "This idea was deleted"
    }
}
