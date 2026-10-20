package com.rendox.grocerygenius.feature.webdavsync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rendox.grocerygenius.data.webdav.WebDavSyncRepository
import com.rendox.grocerygenius.data.webdav.WebDavSyncResult
import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncStatus
import com.rendox.grocerygenius.ui.helpers.UiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SyncButtonViewModel @Inject constructor(
    private val webDavSyncRepository: WebDavSyncRepository
) : ViewModel() {

    /**
     * The status of both the automatic and the manual syncs, or null while sync is disabled.
     */
    val syncStatusFlow: StateFlow<WebDavSyncStatus?> = webDavSyncRepository.config
        .map { it.isEnabled }
        .distinctUntilChanged()
        .flatMapLatest { isEnabled ->
            if (isEnabled) webDavSyncRepository.status else flowOf(null)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    private val _syncFailedEvent = MutableStateFlow<UiEvent<WebDavSyncError>?>(null)
    val syncFailedEvent = _syncFailedEvent.asStateFlow()

    fun syncNow() {
        // a running sync already picks up the latest changes
        if (syncStatusFlow.value?.isSyncing != false) return
        viewModelScope.launch {
            val result = webDavSyncRepository.sync()
            if (result == WebDavSyncResult.FAILURE || result == WebDavSyncResult.RETRYABLE_FAILURE) {
                val error = webDavSyncRepository.status.first().lastError ?: WebDavSyncError.UNKNOWN
                _syncFailedEvent.update {
                    object : UiEvent<WebDavSyncError> {
                        override val data = error
                        override fun onConsumed() {
                            _syncFailedEvent.update { null }
                        }
                    }
                }
            }
        }
    }
}