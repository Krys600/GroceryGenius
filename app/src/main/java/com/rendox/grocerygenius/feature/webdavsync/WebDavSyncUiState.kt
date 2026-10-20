package com.rendox.grocerygenius.feature.webdavsync

import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncStatus

data class WebDavSyncUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isEnabled: Boolean = false,
    val isConnecting: Boolean = false,
    val showRequiredFieldsError: Boolean = false,
    val connectionError: WebDavSyncError? = null,
    val syncStatus: WebDavSyncStatus = WebDavSyncStatus(),
    val isLoading: Boolean = true
)