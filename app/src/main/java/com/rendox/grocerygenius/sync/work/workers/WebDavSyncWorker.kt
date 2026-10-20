package com.rendox.grocerygenius.sync.work.workers

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import com.rendox.grocerygenius.data.webdav.WebDavSyncRepository
import com.rendox.grocerygenius.data.webdav.WebDavSyncResult
import com.rendox.grocerygenius.model.SyncNetworkCondition
import com.rendox.grocerygenius.model.SyncSchedule
import com.rendox.grocerygenius.network.di.Dispatcher
import com.rendox.grocerygenius.network.di.GroceryGeniusDispatchers
import com.rendox.grocerygenius.sync.work.initializers.syncForegroundInfo
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Synchronizes the user's grocery lists with the configured WebDAV server or sync file.
 */
@HiltWorker
class WebDavSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val webDavSyncRepository: WebDavSyncRepository,
    @Dispatcher(GroceryGeniusDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun getForegroundInfo(): ForegroundInfo = appContext.syncForegroundInfo()

    override suspend fun doWork(): Result = withContext(ioDispatcher) {
        when (webDavSyncRepository.sync()) {
            WebDavSyncResult.SUCCESS, WebDavSyncResult.DISABLED -> Result.success()
            WebDavSyncResult.RETRYABLE_FAILURE ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()

            WebDavSyncResult.FAILURE -> Result.failure()
        }
    }

    companion object {
        private const val MAX_RETRIES = 3

        fun oneTimeSyncWork(
            schedule: SyncSchedule,
            initialDelaySeconds: Long = 0
        ): OneTimeWorkRequest = OneTimeWorkRequestBuilder<DelegatingWorker>()
            .setConstraints(schedule.constraints())
            .setInitialDelay(initialDelaySeconds, TimeUnit.SECONDS)
            .setInputData(WebDavSyncWorker::class.delegatedData())
            .build()

        fun periodicSyncWork(schedule: SyncSchedule): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<DelegatingWorker>(
                TimeUnit.MINUTES.toMillis(schedule.intervalMinutes.toLong())
                    .coerceAtLeast(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS),
                TimeUnit.MILLISECONDS
            )
                .setConstraints(schedule.constraints())
                .setInputData(WebDavSyncWorker::class.delegatedData())
                .build()

        private fun SyncSchedule.constraints() = Constraints.Builder()
            .setRequiredNetworkType(
                when (networkCondition) {
                    SyncNetworkCondition.ANY -> NetworkType.CONNECTED
                    SyncNetworkCondition.UNMETERED -> NetworkType.UNMETERED
                    SyncNetworkCondition.NOT_ROAMING -> NetworkType.NOT_ROAMING
                    SyncNetworkCondition.NOT_REQUIRED -> NetworkType.NOT_REQUIRED
                }
            )
            .setRequiresBatteryNotLow(requireBatteryNotLow)
            .build()
    }
}