package com.rendox.grocerygenius.data.webdav

import com.rendox.grocerygenius.model.SyncSchedule
import com.rendox.grocerygenius.model.WebDavSyncConfig
import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncStatus
import kotlinx.coroutines.flow.Flow

interface WebDavSyncRepository {
    val config: Flow<WebDavSyncConfig>
    val status: Flow<WebDavSyncStatus>

    /**
     * Checks the connection to the server, or the access to the sync file, depending on
     * [WebDavSyncConfig.method], and enables sync if it succeeds.
     * @return the error that prevented the connection, or null on success
     */
    suspend fun connect(config: WebDavSyncConfig): WebDavSyncError?

    /**
     * Disables sync. The data on the device and on the server is kept.
     */
    suspend fun disconnect()

    /**
     * Can be changed while sync is enabled.
     */
    suspend fun updateSchedule(schedule: SyncSchedule)

    /**
     * The name of the sync file to show to the user, or null if it can't be determined.
     */
    suspend fun syncFileName(fileUri: String): String?

    suspend fun sync(): WebDavSyncResult
}

enum class WebDavSyncResult {
    SUCCESS,
    DISABLED,
    RETRYABLE_FAILURE,
    FAILURE
}