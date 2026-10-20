package com.rendox.grocerygenius.sync.work.initializers

import android.content.Context
import androidx.room.InvalidationTracker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import com.rendox.grocerygenius.data.webdav.WebDavSyncRepository
import com.rendox.grocerygenius.database.AppDatabase
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
 * Schedules WebDAV sync while it is enabled: on app startup, when the app comes to the
 * foreground, periodically, and shortly after the user changes something.
 */
@Singleton
class WebDavSyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase,
    private val webDavSyncRepository: WebDavSyncRepository,
    @Dispatcher(GroceryGeniusDispatchers.Default) defaultDispatcher: CoroutineDispatcher
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher)

    @Volatile
    private var isSyncEnabled = false

    private val localChangesObserver = object : InvalidationTracker.Observer(SYNCED_TABLES) {
        override fun onInvalidated(tables: Set<String>) {
            // The sync itself also changes these tables, but the request is ignored while it is
            // running. Changes made in the meantime are picked up by the running sync.
            if (isSyncEnabled) enqueueSync(initialDelaySeconds = LOCAL_CHANGES_SYNC_DELAY_SECONDS)
        }
    }

    // This method is called from the app module's Application.onCreate() and should be only done once.
    fun initialize() {
        coroutineScope.launch {
            appDatabase.invalidationTracker.addObserver(localChangesObserver)
            webDavSyncRepository.config
                .map { it.isEnabled }
                .distinctUntilChanged()
                .collect { isEnabled ->
                    isSyncEnabled = isEnabled
                    val workManager = WorkManager.getInstance(context)
                    if (isEnabled) {
                        workManager.enqueueUniquePeriodicWork(
                            WEBDAV_PERIODIC_SYNC_WORK_NAME,
                            ExistingPeriodicWorkPolicy.KEEP,
                            WebDavSyncWorker.periodicSyncWork()
                        )
                        enqueueSync()
                    } else {
                        workManager.cancelUniqueWork(WEBDAV_PERIODIC_SYNC_WORK_NAME)
                        workManager.cancelUniqueWork(WEBDAV_SYNC_WORK_NAME)
                    }
                }
        }
    }

    /**
     * Requests a sync, e.g. when the app comes to the foreground. Does nothing if sync is disabled.
     */
    fun requestSync() {
        if (isSyncEnabled) enqueueSync()
    }

    private fun enqueueSync(initialDelaySeconds: Long = 0) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WEBDAV_SYNC_WORK_NAME,
            // a pending or running sync will pick up the latest changes anyway
            ExistingWorkPolicy.KEEP,
            WebDavSyncWorker.oneTimeSyncWork(initialDelaySeconds)
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