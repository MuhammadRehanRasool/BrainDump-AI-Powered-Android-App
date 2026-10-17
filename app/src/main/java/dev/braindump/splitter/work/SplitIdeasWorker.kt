package dev.braindump.splitter.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.braindump.splitter.domain.repository.IdeaRepository
import dev.braindump.splitter.domain.repository.ProcessResult

/** Drains queued ideas through Gemini Nano, outliving the capture sheet that queued them. */
@HiltWorker
class SplitIdeasWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: IdeaRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = when (repository.processPending()) {
        ProcessResult.DONE -> Result.success()
        ProcessResult.RETRY_LATER -> Result.retry()
    }
}
