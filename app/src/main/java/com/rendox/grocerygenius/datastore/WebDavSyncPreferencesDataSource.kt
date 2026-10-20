package com.rendox.grocerygenius.datastore

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendox.grocerygenius.model.WebDavSyncConfig
import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncStatus
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

class WebDavSyncPreferencesDataSource @Inject constructor(
    @WebDavSyncPreferences private val dataStore: DataStore<Preferences>
) {
    private val preferences: Flow<Preferences> = dataStore.data.catch { exception ->
        if (exception !is IOException) throw exception
        Log.e("WebDavSyncPreferences", "Failed to read WebDAV sync preferences", exception)
        emit(emptyPreferences())
    }

    val config: Flow<WebDavSyncConfig> = preferences
        .map { preferences ->
            WebDavSyncConfig(
                serverUrl = preferences[SERVER_URL_KEY] ?: "",
                username = preferences[USERNAME_KEY] ?: "",
                password = preferences[PASSWORD_KEY] ?: "",
                isEnabled = preferences[ENABLED_KEY] ?: false
            )
        }
        .distinctUntilChanged()

    /**
     * The result of the last sync. [WebDavSyncStatus.isSyncing] is always false.
     */
    val lastSyncResult: Flow<WebDavSyncStatus> = preferences
        .map { preferences ->
            WebDavSyncStatus(
                lastSuccessfulSync = preferences[LAST_SUCCESSFUL_SYNC_KEY],
                lastError = preferences[LAST_ERROR_KEY]?.let { errorName ->
                    WebDavSyncError.entries.find { it.name == errorName }
                }
            )
        }
        .distinctUntilChanged()

    suspend fun saveConfig(config: WebDavSyncConfig) {
        dataStore.edit { preferences ->
            preferences[SERVER_URL_KEY] = config.serverUrl
            preferences[USERNAME_KEY] = config.username
            preferences[PASSWORD_KEY] = config.password
            preferences[ENABLED_KEY] = config.isEnabled
            preferences.remove(LAST_SUCCESSFUL_SYNC_KEY)
            preferences.remove(LAST_ERROR_KEY)
        }
    }

    /**
     * Disables sync and forgets the password. The server URL and the username are kept,
     * so that the user doesn't have to enter them again.
     */
    suspend fun disable() {
        dataStore.edit { preferences ->
            preferences[ENABLED_KEY] = false
            preferences.remove(PASSWORD_KEY)
            preferences.remove(LAST_SUCCESSFUL_SYNC_KEY)
            preferences.remove(LAST_ERROR_KEY)
        }
    }

    suspend fun recordSyncSuccess(timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[LAST_SUCCESSFUL_SYNC_KEY] = timestamp
            preferences.remove(LAST_ERROR_KEY)
        }
    }

    suspend fun recordSyncFailure(error: WebDavSyncError) {
        dataStore.edit { preferences ->
            preferences[LAST_ERROR_KEY] = error.name
        }
    }

    companion object {
        val ENABLED_KEY = booleanPreferencesKey("enabled")
        val SERVER_URL_KEY = stringPreferencesKey("server_url")
        val USERNAME_KEY = stringPreferencesKey("username")
        val PASSWORD_KEY = stringPreferencesKey("password")
        val LAST_SUCCESSFUL_SYNC_KEY = longPreferencesKey("last_successful_sync")
        val LAST_ERROR_KEY = stringPreferencesKey("last_error")
    }
}