package com.rendox.grocerygenius.feature.webdavsync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendox.grocerygenius.data.webdav.WebDavSyncRepository
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
                    serverUrl = config.serverUrl,
                    username = config.username,
                    password = config.password
                )
            }
            webDavSyncRepository.config
                .combine(webDavSyncRepository.status) { latestConfig, status ->
                    latestConfig.isEnabled to status
                }
                .collectLatest { (isEnabled, status) ->
                    _uiStateFlow.update {
                        it.copy(isEnabled = isEnabled, syncStatus = status, isLoading = false)
                    }
                }
        }
    }

    fun onIntent(intent: WebDavSyncIntent) {
        when (intent) {
            is WebDavSyncIntent.UpdateServerUrl -> _uiStateFlow.update {
                it.copy(serverUrl = intent.serverUrl, connectionError = null, showRequiredFieldsError = false)
            }

            is WebDavSyncIntent.UpdateUsername -> _uiStateFlow.update {
                it.copy(username = intent.username, connectionError = null, showRequiredFieldsError = false)
            }

            is WebDavSyncIntent.UpdatePassword -> _uiStateFlow.update {
                it.copy(password = intent.password, connectionError = null, showRequiredFieldsError = false)
            }

            is WebDavSyncIntent.Connect -> connect()

            is WebDavSyncIntent.Disconnect -> viewModelScope.launch {
                webDavSyncRepository.disconnect()
                _uiStateFlow.update { it.copy(password = "") }
            }

            is WebDavSyncIntent.SyncNow -> viewModelScope.launch {
                webDavSyncRepository.sync()
            }
        }
    }

    private fun connect() {
        val uiState = _uiStateFlow.value
        if (uiState.isConnecting || uiState.isEnabled) return
        if (uiState.serverUrl.isBlank() || uiState.username.isBlank() || uiState.password.isEmpty()) {
            _uiStateFlow.update { it.copy(showRequiredFieldsError = true) }
            return
        }
        viewModelScope.launch {
            _uiStateFlow.update { it.copy(isConnecting = true, connectionError = null) }
            val error = webDavSyncRepository.connect(
                WebDavSyncConfig(
                    serverUrl = uiState.serverUrl,
                    username = uiState.username,
                    password = uiState.password
                )
            )
            _uiStateFlow.update { it.copy(isConnecting = false, connectionError = error) }
        }
    }
}