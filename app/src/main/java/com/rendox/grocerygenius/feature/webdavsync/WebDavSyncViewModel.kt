package com.rendox.grocerygenius.feature.webdavsync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendox.grocerygenius.data.webdav.WebDavSyncRepository
import com.rendox.grocerygenius.model.SyncMethod
import com.rendox.grocerygenius.model.WebDavSyncConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class WebDavSyncViewModel @Inject constructor(
    private val webDavSyncRepository: WebDavSyncRepository
) : ViewModel() {

    private val _uiStateFlow = MutableStateFlow(WebDavSyncUiState())
    val uiStateFlow = _uiStateFlow.asStateFlow()

    init {
        viewModelScope.launch {
            val config = webDavSyncRepository.config.first()
            _uiStateFlow.update {
                it.copy(
                    // the file picker is easier to set up, so it is suggested first
                    method = if (config.isEnabled) config.method else it.method,
                    serverUrl = config.serverUrl,
                    username = config.username,
                    password = config.password
                )
            }
            webDavSyncRepository.config
                .combine(webDavSyncRepository.status) { latestConfig, status ->
                    latestConfig to status
                }
                .collectLatest { (latestConfig, status) ->
                    val syncFileName = if (latestConfig.isEnabled && latestConfig.method == SyncMethod.FILE) {
                        webDavSyncRepository.syncFileName(latestConfig.fileUri)
                    } else {
                        null
                    }
                    _uiStateFlow.update {
                        it.copy(
                            method = if (latestConfig.isEnabled) latestConfig.method else it.method,
                            isEnabled = latestConfig.isEnabled,
                            schedule = latestConfig.schedule,
                            syncFileName = syncFileName,
                            syncStatus = status,
                            isLoading = false
                        )
                    }
                }
        }
    }

    fun onIntent(intent: WebDavSyncIntent) {
        when (intent) {
            is WebDavSyncIntent.SelectMethod -> _uiStateFlow.update {
                // the method can only be changed while disconnected
                if (it.isEnabled || it.isConnecting) {
                    it
                } else {
                    it.copy(method = intent.method, connectionError = null, showRequiredFieldsError = false)
                }
            }

            is WebDavSyncIntent.UpdateServerUrl -> _uiStateFlow.update {
                it.copy(serverUrl = intent.serverUrl, connectionError = null, showRequiredFieldsError = false)
            }

            is WebDavSyncIntent.UpdateUsername -> _uiStateFlow.update {
                it.copy(username = intent.username, connectionError = null, showRequiredFieldsError = false)
            }

            is WebDavSyncIntent.UpdatePassword -> _uiStateFlow.update {
                it.copy(password = intent.password, connectionError = null, showRequiredFieldsError = false)
            }

            is WebDavSyncIntent.Connect -> connectToServer()

            is WebDavSyncIntent.ConnectToFile -> connect(
                WebDavSyncConfig(method = SyncMethod.FILE, fileUri = intent.fileUri)
            )

            is WebDavSyncIntent.Disconnect -> viewModelScope.launch {
                webDavSyncRepository.disconnect()
                _uiStateFlow.update { it.copy(password = "") }
            }

            is WebDavSyncIntent.SyncNow -> viewModelScope.launch {
                webDavSyncRepository.sync()
            }

            is WebDavSyncIntent.UpdateSchedule -> viewModelScope.launch {
                webDavSyncRepository.updateSchedule(intent.schedule)
            }
        }
    }

    private fun connectToServer() {
        val uiState = _uiStateFlow.value
        if (uiState.serverUrl.isBlank() || uiState.username.isBlank() || uiState.password.isEmpty()) {
            _uiStateFlow.update { it.copy(showRequiredFieldsError = true) }
            return
        }
        connect(
            WebDavSyncConfig(
                method = SyncMethod.WEBDAV,
                serverUrl = uiState.serverUrl,
                username = uiState.username,
                password = uiState.password
            )
        )
    }

    private fun connect(config: WebDavSyncConfig) {
        val uiState = _uiStateFlow.value
        if (uiState.isConnecting || uiState.isEnabled) return
        viewModelScope.launch {
            _uiStateFlow.update { it.copy(isConnecting = true, connectionError = null) }
            val error = webDavSyncRepository.connect(config)
            _uiStateFlow.update { it.copy(isConnecting = false, connectionError = error) }
        }
    }
}