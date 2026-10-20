package com.rendox.grocerygenius.model

data class WebDavSyncConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isEnabled: Boolean = false
)

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