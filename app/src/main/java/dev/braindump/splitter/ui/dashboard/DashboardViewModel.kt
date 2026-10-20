package dev.braindump.splitter.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.braindump.splitter.di.ApplicationScope
import dev.braindump.splitter.domain.model.AiStatus
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.domain.model.IdeaCategory
import dev.braindump.splitter.domain.model.RefineOutcome
import dev.braindump.splitter.domain.repository.IdeaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class DaySection(
    val epochDay: Long,
    val ideas: List<Idea>,
)

data class DashboardUiState(
    val sections: List<DaySection> = emptyList(),
    val aiStatus: AiStatus = AiStatus.Unknown,
    val refine: RefineState = RefineState(),
    val isLoading: Boolean = true,
)

/** The refine chat's request in flight, or the last one that failed (so it can be retried). */
data class RefineState(
    val ideaId: Long? = null,
    val pendingAsk: String? = null,
    val failedAsk: String? = null,
    val error: String? = null,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: IdeaRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val refining = MutableStateFlow(RefineState())

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.observeIdeas().map(::groupByDay).flowOn(Dispatchers.Default),
        repository.aiStatus,
        refining,
    ) { sections, aiStatus, refineState ->
        DashboardUiState(
            sections = sections,
            aiStatus = aiStatus,
            refine = refineState,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    /**
     * Runs in the app scope so a dismissed sheet still gets its answer saved; the card
     * updates in place through the ideas flow.
     */
    fun refine(ideaId: Long, ask: String) {
        if (refining.value.pendingAsk != null || ask.isBlank()) return
        refining.value = RefineState(ideaId = ideaId, pendingAsk = ask)
        appScope.launch {
            refining.value = when (val outcome = repository.refine(ideaId, ask)) {
                is RefineOutcome.Updated -> RefineState()
                is RefineOutcome.Failed -> RefineState(ideaId = ideaId, failedAsk = ask, error = outcome.reason)
            }
        }
    }

    fun clearRefineError() {
        refining.update { if (it.pendingAsk == null) RefineState() else it }
    }

    /** Gemini Nano only serves foreground apps, so this is where the queue gets drained. */
    fun onForeground() {
        viewModelScope.launch {
            repository.warmUpAi()
            repository.splitPending()
        }
    }

    fun capture(text: String, image: android.graphics.Bitmap?) {
        appScope.launch { repository.captureAndSplit(text, image) }
    }

    fun retry(ideaId: Long) {
        appScope.launch { repository.retry(ideaId) }
    }

    fun setCategory(ideaId: Long, category: IdeaCategory) {
        appScope.launch { repository.setCategory(ideaId, category) }
    }

    fun delete(ideaId: Long) {
        appScope.launch { repository.delete(ideaId) }
    }
}

internal fun todayEpochDay(): Long = LocalDate.now(ZoneId.systemDefault()).toEpochDay()

/** Ideas grouped by local day, newest first. */
internal fun groupByDay(ideas: List<Idea>): List<DaySection> {
    if (ideas.isEmpty()) return emptyList()
    val zone = ZoneId.systemDefault()
    val sections = ArrayList<DaySection>()
    var currentDay = Long.MIN_VALUE
    var bucket = ArrayList<Idea>()

    // Ideas arrive newest first, so each day is one contiguous run.
    for (idea in ideas) {
        val day = Instant.ofEpochMilli(idea.createdAt).atZone(zone).toLocalDate().toEpochDay()
        if (day != currentDay) {
            if (bucket.isNotEmpty()) sections += DaySection(currentDay, bucket)
            currentDay = day
            bucket = ArrayList()
        }
        bucket += idea
    }
    if (bucket.isNotEmpty()) sections += DaySection(currentDay, bucket)
    return sections
}
