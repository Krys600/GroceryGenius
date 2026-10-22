package com.rendox.grocerygenius.sync.work.initializers

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.rendox.grocerygenius.data.webdav.WebDavSyncRepository
import com.rendox.grocerygenius.database.AppDatabase
import com.rendox.grocerygenius.model.SyncSchedule
import com.rendox.grocerygenius.network.di.Dispatcher
import com.rendox.grocerygenius.network.di.GroceryGeniusDispatchers
import com.rendox.grocerygenius.sync.work.workers.WebDavSyncWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Schedules sync while it is enabled: on app startup, periodically, and, if
 * [SyncSchedule.syncOnChanges] is set, when the app comes to the foreground and shortly after
 * the user changes something. All of them wait until the conditions of the [SyncSchedule] are met.
 */
@Singleton
class WebDavSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase,
    private val webDavSyncRepository: WebDavSyncRepository,
    @Dispatcher(GroceryGeniusDispatchers.Default) defaultDispatcher: CoroutineDispatcher
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher)

    /**
     * The schedule while sync is enabled, null otherwise.
     */
    @Volatile
    private var schedule: SyncSchedule? = null

    private val localChangesObserver = object : InvalidationTracker.Observer(SYNCED_TABLES) {
        override fun onInvalidated(tables: Set<String>) {
            // The sync itself also changes these tables, but the request is ignored while it is
            // running. Changes made in the meantime are picked up by the running sync.
            val schedule = schedule ?: return
            if (schedule.syncOnChanges) {
                enqueueSync(schedule, initialDelaySeconds = LOCAL_CHANGES_SYNC_DELAY_SECONDS)
            }
        }
    }

    // This method is called from the app module's Application.onCreate() and should be only done once.
    fun initialize() {
        coroutineScope.launch {
            appDatabase.invalidationTracker.addObserver(localChangesObserver)
            webDavSyncRepository.config
                .map { config -> config.schedule.takeIf { config.isEnabled } }
                .distinctUntilChanged()
                .collect { newSchedule ->
                    val wasEnabled = schedule != null
                    schedule = newSchedule
                    val workManager = WorkManager.getInstance(context)
                    if (newSchedule != null) {
                        if (newSchedule.intervalMinutes == SyncSchedule.BACKGROUND_SYNC_DISABLED) {
                            workManager.cancelUniqueWork(WEBDAV_PERIODIC_SYNC_WORK_NAME)
                        } else {
                            workManager.enqueueUniquePeriodicWork(
                                WEBDAV_PERIODIC_SYNC_WORK_NAME,
                                // applies a changed interval or constraints to the existing work
                                ExistingPeriodicWorkPolicy.UPDATE,
                                WebDavSyncWorker.periodicSyncWork(newSchedule)
                            )
                        }
                        // sync right away when sync gets enabled or the app starts,
                        // but not every time the schedule changes
                        if (!wasEnabled) enqueueSync(newSchedule)
                    } else {
                        workManager.cancelUniqueWork(WEBDAV_PERIODIC_SYNC_WORK_NAME)
                        workManager.cancelUniqueWork(WEBDAV_SYNC_WORK_NAME)
                    }
                }
        }
    }

    /**
     * Requests a sync when the app comes to the foreground. Does nothing if sync is disabled
     * or [SyncSchedule.syncOnChanges] is not set.
     */
    fun requestSync() {
        val schedule = schedule ?: return
        if (schedule.syncOnChanges) enqueueSync(schedule)
    }

    private fun enqueueSync(
        schedule: SyncSchedule,
        initialDelaySeconds: Long = 0
    ) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WEBDAV_SYNC_WORK_NAME,
            // a pending or running sync will pick up the latest changes anyway
            ExistingWorkPolicy.KEEP,
            WebDavSyncWorker.oneTimeSyncWork(schedule, initialDelaySeconds)
        )
    }

    companion object {
        private val SYNCED_TABLES = arrayOf(
            "GroceryListEntity",
            "GroceryEntity",
            "ProductEntity",
            "CategoryEntity"
        )
        private const val LOCAL_CHANGES_SYNC_DELAY_SECONDS = 5L
    }
}

// These names should not be changed otherwise the app may have concurrent sync requests running
internal const val WEBDAV_SYNC_WORK_NAME = "WebDavSyncWorkName"
internal const val WEBDAV_PERIODIC_SYNC_WORK_NAME = "WebDavPeriodicSyncWorkName"