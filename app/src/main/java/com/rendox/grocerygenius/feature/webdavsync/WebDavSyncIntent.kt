package com.rendox.grocerygenius.feature.webdavsync

import com.rendox.grocerygenius.model.SyncMethod
import com.rendox.grocerygenius.model.SyncSchedule

sealed interface WebDavSyncIntent {
    data class SelectMethod(val method: SyncMethod) : WebDavSyncIntent
    data class UpdateServerUrl(val serverUrl: String) : WebDavSyncIntent
    data class UpdateUsername(val username: String) : WebDavSyncIntent
    data class UpdatePassword(val password: String) : WebDavSyncIntent
    data object Connect : WebDavSyncIntent

    /**
     * The user picked or created the sync file with the system file picker.
     */
    data class ConnectToFile(val fileUri: String) : WebDavSyncIntent
    data object Disconnect : WebDavSyncIntent
    data object SyncNow : WebDavSyncIntent
    data class UpdateSchedule(val schedule: SyncSchedule) : WebDavSyncIntent
}