package com.rendox.grocerygenius.data.webdav

import android.net.Uri
import android.util.Log
import com.rendox.grocerygenius.datastore.WebDavSyncPreferencesDataSource
import com.rendox.grocerygenius.model.SyncMethod
import com.rendox.grocerygenius.model.SyncSchedule
import com.rendox.grocerygenius.model.WebDavSyncConfig
import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncException
import com.rendox.grocerygenius.model.WebDavSyncStatus
import com.rendox.grocerygenius.network.di.Dispatcher
import com.rendox.grocerygenius.network.di.GroceryGeniusDispatchers
import com.rendox.grocerygenius.network.document.DocumentFileClient
import com.rendox.grocerygenius.network.webdav.WebDavClient
import com.rendox.grocerygenius.sync.webdav.RemoteSnapshotStore
import com.rendox.grocerygenius.sync.webdav.SyncBaseStore
import com.rendox.grocerygenius.sync.webdav.WebDavSyncEngine
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WebDavSyncRepositoryImpl @Inject constructor(
    private val preferencesDataSource: WebDavSyncPreferencesDataSource,
    private val webDavClient: WebDavClient,
    private val documentFileClient: DocumentFileClient,
    private val syncEngine: WebDavSyncEngine,
    private val syncBaseStore: SyncBaseStore,
    @Dispatcher(GroceryGeniusDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) : WebDavSyncRepository {
    // sync runs from several workers and from the UI, but only one sync may run at a time
    private val mutex = Mutex()
    private val isSyncing = MutableStateFlow(false)

    override val config: Flow<WebDavSyncConfig> = preferencesDataSource.config

    override val status: Flow<WebDavSyncStatus> =
        combine(preferencesDataSource.lastSyncResult, isSyncing) { lastSyncResult, isSyncing ->
            lastSyncResult.copy(isSyncing = isSyncing)
        }

    override suspend fun connect(config: WebDavSyncConfig): WebDavSyncError? {
        val newConfig = when (config.method) {
            SyncMethod.WEBDAV -> config.copy(
                serverUrl = config.serverUrl.trim(),
                username = config.username.trim(),
                fileUri = ""
            )

            SyncMethod.FILE -> config.copy(password = "")
        }.copy(isEnabled = true)
        try {
            when (newConfig.method) {
                SyncMethod.WEBDAV -> webDavClient.checkConnection(newConfig)
                SyncMethod.FILE -> documentFileClient.connect(Uri.parse(newConfig.fileUri))
            }
        } catch (exception: WebDavSyncException) {
            Log.w(TAG, "Failed to connect to ${newConfig.method}", exception)
            return exception.error
        }
        mutex.withLock {
            // without a base, the first sync merges the local data with the data on the server
            syncBaseStore.clear()
            preferencesDataSource.saveConfig(newConfig)
        }
        return null
    }

    override suspend fun disconnect() {
        mutex.withLock {
            val config = preferencesDataSource.config.first()
            if (config.method == SyncMethod.FILE && config.fileUri.isNotEmpty()) {
                documentFileClient.disconnect(Uri.parse(config.fileUri))
            }
            preferencesDataSource.disable()
            syncBaseStore.clear()
        }
    }

    override suspend fun updateSchedule(schedule: SyncSchedule) {
        preferencesDataSource.saveSchedule(schedule)
    }

    override suspend fun syncFileName(fileUri: String): String? = documentFileClient.displayName(Uri.parse(fileUri))

    private fun remoteSnapshotStore(config: WebDavSyncConfig): RemoteSnapshotStore = when (config.method) {
        SyncMethod.WEBDAV -> webDavClient.remoteSnapshotStore(config)
        SyncMethod.FILE -> documentFileClient.remoteSnapshotStore(Uri.parse(config.fileUri))
    }

    override suspend fun sync(): WebDavSyncResult = withContext(ioDispatcher) {
        mutex.withLock {
            val config = preferencesDataSource.config.first()
            if (!config.isEnabled) return@withLock WebDavSyncResult.DISABLED

            isSyncing.value = true
            try {
                syncEngine.sync(remoteSnapshotStore(config))
                preferencesDataSource.recordSyncSuccess(System.currentTimeMillis())
                WebDavSyncResult.SUCCESS
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: WebDavSyncException) {
                Log.w(TAG, "${config.method} sync failed", exception)
                preferencesDataSource.recordSyncFailure(exception.error)
                if (exception.error.isRetryable) WebDavSyncResult.RETRYABLE_FAILURE else WebDavSyncResult.FAILURE
            } catch (exception: Exception) {
                // e.g. a database error or a failure to save the sync base
                Log.e(TAG, "${config.method} sync failed unexpectedly", exception)
                preferencesDataSource.recordSyncFailure(WebDavSyncError.UNKNOWN)
                WebDavSyncResult.RETRYABLE_FAILURE
            } finally {
                isSyncing.value = false
            }
        }
    }

    companion object {
        private const val TAG = "WebDavSyncRepository"
    }
}