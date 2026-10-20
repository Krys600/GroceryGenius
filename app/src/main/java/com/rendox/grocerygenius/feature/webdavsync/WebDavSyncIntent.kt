package com.rendox.grocerygenius.feature.webdavsync

sealed interface WebDavSyncIntent {
    data class UpdateServerUrl(val serverUrl: String) : WebDavSyncIntent
    data class UpdateUsername(val username: String) : WebDavSyncIntent
    data class UpdatePassword(val password: String) : WebDavSyncIntent
    data object Connect : WebDavSyncIntent
    data object Disconnect : WebDavSyncIntent
    data object SyncNow : WebDavSyncIntent
}