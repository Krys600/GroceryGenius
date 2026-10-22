package com.rendox.grocerygenius.feature.webdavsync

import com.rendox.grocerygenius.model.SyncMethod
import com.rendox.grocerygenius.model.SyncSchedule
import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncStatus

data class WebDavSyncUiState(
    val method: SyncMethod = SyncMethod.FILE,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    /**
     * The name of the connected sync file, if it can be determined.
     */
    val syncFileName: String? = null,
    val schedule: SyncSchedule = SyncSchedule(),
    val isEnabled: Boolean = false,
    val isConnecting: Boolean = false,
    val showRequiredFieldsError: Boolean = false,
    val connectionError: WebDavSyncError? = null,
    val syncStatus: WebDavSyncStatus = WebDavSyncStatus(),
    val isLoading: Boolean = true
)