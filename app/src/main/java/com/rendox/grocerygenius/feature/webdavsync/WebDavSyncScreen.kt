package com.rendox.grocerygenius.feature.webdavsync

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendox.grocerygenius.R
import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncStatus
import com.rendox.grocerygenius.network.webdav.WebDavClient
import com.rendox.grocerygenius.ui.theme.GroceryGeniusTheme
import com.rendox.grocerygenius.ui.theme.TopAppBarActionsHorizontalPadding
import com.rendox.grocerygenius.ui.theme.TopAppBarSmallHeight

@Composable
fun WebDavSyncRoute(
    viewModel: WebDavSyncViewModel = hiltViewModel(),
    navigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiStateFlow.collectAsStateWithLifecycle()
    WebDavSyncScreen(
        modifier = Modifier.fillMaxSize(),
        uiState = uiState,
        onIntent = viewModel::onIntent,
        navigateBack = navigateBack
    )
}

@Composable
private fun WebDavSyncScreen(
    modifier: Modifier = Modifier,
    uiState: WebDavSyncUiState,
    onIntent: (WebDavSyncIntent) -> Unit,
    navigateBack: () -> Unit
) {
    Column(
        modifier = modifier
            .systemBarsPadding()
            .imePadding()
    ) {
        WebDavSyncTopAppBar(
            modifier = Modifier.fillMaxWidth(),
            navigateBack = navigateBack
        )
        if (!uiState.isLoading) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.webdav_sync_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                WebDavSyncCredentials(
                    uiState = uiState,
                    onIntent = onIntent
                )
                val errorMessage = when {
                    uiState.showRequiredFieldsError -> stringResource(R.string.webdav_sync_required_fields_error)
                    uiState.connectionError != null -> uiState.connectionError.asLocalString()
                    else -> null
                }
                errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (uiState.isEnabled) {
                    WebDavSyncStatusInfo(status = uiState.syncStatus)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = { onIntent(WebDavSyncIntent.Disconnect) }
                        ) {
                            Text(text = stringResource(R.string.webdav_sync_disconnect))
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            onClick = { onIntent(WebDavSyncIntent.SyncNow) },
                            enabled = !uiState.syncStatus.isSyncing
                        ) {
                            Text(text = stringResource(R.string.webdav_sync_sync_now))
                        }
                    }
                } else {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onIntent(WebDavSyncIntent.Connect) },
                        enabled = !uiState.isConnecting
                    ) {
                        if (uiState.isConnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(text = stringResource(R.string.webdav_sync_connect))
                        }
                    }
                }
                Text(
                    text = stringResource(
                        R.string.webdav_sync_storage_note,
                        "${WebDavClient.REMOTE_FOLDER_NAME}/${WebDavClient.REMOTE_FILE_NAME}"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WebDavSyncTopAppBar(
    modifier: Modifier = Modifier,
    navigateBack: () -> Unit
) {
    Row(
        modifier = modifier
            .height(TopAppBarSmallHeight)
            .padding(horizontal = TopAppBarActionsHorizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = navigateBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Default.ArrowBack,
                contentDescription = stringResource(R.string.back)
            )
        }
        Text(
            modifier = Modifier.padding(start = 8.dp),
            text = stringResource(R.string.webdav_sync_title),
            style = MaterialTheme.typography.titleLarge
        )
    }
}

@Composable
private fun WebDavSyncCredentials(
    uiState: WebDavSyncUiState,
    onIntent: (WebDavSyncIntent) -> Unit
) {
    // the connection settings can only be changed while disconnected
    val fieldsEnabled = !uiState.isEnabled && !uiState.isConnecting
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = uiState.serverUrl,
        onValueChange = { onIntent(WebDavSyncIntent.UpdateServerUrl(it)) },
        enabled = fieldsEnabled,
        label = { Text(text = stringResource(R.string.webdav_sync_server_url)) },
        placeholder = { Text(text = stringResource(R.string.webdav_sync_server_url_placeholder)) },
        supportingText = { Text(text = stringResource(R.string.webdav_sync_server_url_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            autoCorrect = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next
        )
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = uiState.username,
        onValueChange = { onIntent(WebDavSyncIntent.UpdateUsername(it)) },
        enabled = fieldsEnabled,
        label = { Text(text = stringResource(R.string.webdav_sync_username)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            autoCorrect = false,
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Next
        )
    )
    OutlinedTextField(
        modifier = Modifier.fillMaxWidth(),
        value = uiState.password,
        onValueChange = { onIntent(WebDavSyncIntent.UpdatePassword(it)) },
        enabled = fieldsEnabled,
        label = { Text(text = stringResource(R.string.webdav_sync_password)) },
        supportingText = { Text(text = stringResource(R.string.webdav_sync_password_hint)) },
        singleLine = true,
        visualTransformation = if (passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        keyboardOptions = KeyboardOptions(
            autoCorrect = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(onDone = { onIntent(WebDavSyncIntent.Connect) }),
        trailingIcon = {
            if (fieldsEnabled) {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    if (passwordVisible) {
                        Icon(
                            painter = painterResource(R.drawable.baseline_visibility_off_24),
                            contentDescription = stringResource(R.string.webdav_sync_hide_password)
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.baseline_visibility_24),
                            contentDescription = stringResource(R.string.webdav_sync_show_password)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun WebDavSyncStatusInfo(
    modifier: Modifier = Modifier,
    status: WebDavSyncStatus
) {
    val context = LocalContext.current
    val statusText = when {
        status.isSyncing -> stringResource(R.string.webdav_sync_status_syncing)
        status.lastError != null -> stringResource(
            R.string.webdav_sync_status_failed,
            status.lastError.asLocalString()
        )

        status.lastSuccessfulSync != null -> stringResource(
            R.string.webdav_sync_status_last_synced,
            DateUtils.formatDateTime(
                context,
                status.lastSuccessfulSync,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME
            )
        )

        else -> stringResource(R.string.webdav_sync_status_not_synced)
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (status.isSyncing) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(16.dp),
                strokeWidth = 2.dp
            )
        } else {
            Icon(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .size(16.dp),
                painter = painterResource(R.drawable.baseline_sync_24),
                contentDescription = null
            )
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (status.lastError != null && !status.isSyncing) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }
}

@Composable
private fun WebDavSyncError.asLocalString() = stringResource(
    when (this) {
        WebDavSyncError.INVALID_URL -> R.string.webdav_sync_error_invalid_url
        WebDavSyncError.INSECURE_CONNECTION -> R.string.webdav_sync_error_insecure_connection
        WebDavSyncError.UNTRUSTED_CERTIFICATE -> R.string.webdav_sync_error_untrusted_certificate
        WebDavSyncError.AUTHENTICATION_FAILED -> R.string.webdav_sync_error_authentication_failed
        WebDavSyncError.NOT_FOUND -> R.string.webdav_sync_error_not_found
        WebDavSyncError.NOT_A_WEBDAV_FOLDER -> R.string.webdav_sync_error_not_a_webdav_folder
        WebDavSyncError.REDIRECT -> R.string.webdav_sync_error_redirect
        WebDavSyncError.SERVER_ERROR -> R.string.webdav_sync_error_server_error
        WebDavSyncError.NETWORK_ERROR -> R.string.webdav_sync_error_network_error
        WebDavSyncError.INVALID_REMOTE_DATA -> R.string.webdav_sync_error_invalid_remote_data
        WebDavSyncError.UNSUPPORTED_REMOTE_VERSION -> R.string.webdav_sync_error_unsupported_remote_version
        WebDavSyncError.TOO_MANY_CONFLICTS -> R.string.webdav_sync_error_too_many_conflicts
        WebDavSyncError.UNKNOWN -> R.string.webdav_sync_error_unknown
    }
)

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun WebDavSyncScreenPreview() {
    GroceryGeniusTheme {
        Surface {
            WebDavSyncScreen(
                uiState = WebDavSyncUiState(
                    serverUrl = "https://cloud.example.com/remote.php/dav/files/jane/",
                    username = "jane",
                    password = "app-password",
                    isEnabled = true,
                    syncStatus = WebDavSyncStatus(lastSuccessfulSync = 1_700_000_000_000),
                    isLoading = false
                ),
                onIntent = {},
                navigateBack = {}
            )
        }
    }
}