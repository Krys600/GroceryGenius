package com.rendox.grocerygenius.data.webdav

import com.rendox.grocerygenius.model.WebDavSyncConfig
import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncStatus
import kotlinx.coroutines.flow.Flow

interface WebDavSyncRepository {
    val config: Flow<WebDavSyncConfig>
    val status: Flow<WebDavSyncStatus>

    /**
     * Checks the connection to the server and enables sync if it succeeds.
     * @return the error that prevented the connection, or null on success
     */
    suspend fun connect(config: WebDavSyncConfig): WebDavSyncError?

    /**
     * Disables sync. The data on the device and on the server is kept.
     */
    suspend fun disconnect()

    suspend fun sync(): WebDavSyncResult
}

enum class WebDavSyncResult {
    SUCCESS,
    DISABLED,
    RETRYABLE_FAILURE,
    FAILURE
}