package com.rendox.grocerygenius.model

data class WebDavSyncConfig(
    val method: SyncMethod = SyncMethod.WEBDAV,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    /**
     * The content URI of the sync file picked with the system file picker, used by [SyncMethod.FILE].
     */
    val fileUri: String = "",
    val isEnabled: Boolean = false,
    val schedule: SyncSchedule = SyncSchedule()
)

enum class SyncMethod {
    /**
     * A file picked with the system file picker, e.g. from the Nextcloud app's storage provider.
     */
    FILE,
    WEBDAV
}

data class SyncSchedule(
    /**
     * How often to sync in the background, or [BACKGROUND_SYNC_DISABLED].
     */
    val intervalMinutes: Int = 15,
    val networkCondition: SyncNetworkCondition = SyncNetworkCondition.ANY,
    val requireBatteryNotLow: Boolean = false,
    /**
     * Whether to sync when the app comes to the foreground and shortly after local changes.
     */
    val syncOnChanges: Boolean = true
) {
    companion object {
        const val BACKGROUND_SYNC_DISABLED = 0

        val INTERVAL_OPTIONS_MINUTES = listOf(BACKGROUND_SYNC_DISABLED, 15, 30, 60, 180, 360, 720, 1440)
    }
}

enum class SyncNetworkCondition {
    ANY,
    UNMETERED,
    NOT_ROAMING,

    /**
     * Useful for sync files stored on the device, e.g. in a folder synced by another app.
     */
    NOT_REQUIRED
}

data class WebDavSyncStatus(
    val isSyncing: Boolean = false,
    val lastSuccessfulSync: Long? = null,
    val lastError: WebDavSyncError? = null
)

enum class WebDavSyncError(val isRetryable: Boolean) {
    INVALID_URL(isRetryable = false),
    INSECURE_CONNECTION(isRetryable = false),
    UNTRUSTED_CERTIFICATE(isRetryable = false),
    AUTHENTICATION_FAILED(isRetryable = false),
    NOT_FOUND(isRetryable = false),
    NOT_A_WEBDAV_FOLDER(isRetryable = false),
    REDIRECT(isRetryable = false),
    SERVER_ERROR(isRetryable = true),
    NETWORK_ERROR(isRetryable = true),
    FILE_NOT_ACCESSIBLE(isRetryable = false),
    FILE_ERROR(isRetryable = true),
    INVALID_REMOTE_DATA(isRetryable = false),
    UNSUPPORTED_REMOTE_VERSION(isRetryable = false),
    TOO_MANY_CONFLICTS(isRetryable = true),
    UNKNOWN(isRetryable = true)
}

class WebDavSyncException(
    val error: WebDavSyncError,
    message: String? = null,
    cause: Throwable? = null
) : Exception(message ?: error.name, cause)