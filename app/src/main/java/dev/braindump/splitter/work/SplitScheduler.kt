package dev.braindump.splitter.work

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SplitScheduler @Inject constructor(
    private val workManager: WorkManager,
) {
    /**
     * Makes sure a worker will drain the queue soon.
     *
     * A worker that is mid-inference is left alone and a fresh one is chained after it,
     * so nothing captured during the run is missed. A worker sitting in retry backoff is
     * replaced so the new attempt runs right away.
     */
    suspend fun enqueue() {
        val running = workManager.getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
            .first()
            .any { it.state == WorkInfo.State.RUNNING }

        val request = OneTimeWorkRequestBuilder<SplitIdeasWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()

        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private companion object {
        const val UNIQUE_WORK_NAME = "split_ideas"
        const val BACKOFF_SECONDS = 20L
    }
}
