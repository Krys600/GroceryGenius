package com.rendox.grocerygenius.feature.webdavsync

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.pluralStringResource
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
import com.rendox.grocerygenius.model.SyncMethod
import com.rendox.grocerygenius.model.SyncNetworkCondition
import com.rendox.grocerygenius.model.SyncSchedule
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
                SyncMethodSelector(
                    method = uiState.method,
                    // the method can only be changed while disconnected
                    enabled = !uiState.isEnabled && !uiState.isConnecting,
                    onMethodSelected = { onIntent(WebDavSyncIntent.SelectMethod(it)) }
                )
                when (uiState.method) {
                    SyncMethod.FILE -> FileSyncDescription(
                        syncFileName = uiState.syncFileName.takeIf { uiState.isEnabled }
                    )
                    SyncMethod.WEBDAV -> WebDavSyncCredentials(
                        uiState = uiState,
                        onIntent = onIntent
                    )
                }
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
                    when (uiState.method) {
                        SyncMethod.FILE -> FileSyncConnectButtons(
                            isConnecting = uiState.isConnecting,
                            onFileSelected = { onIntent(WebDavSyncIntent.ConnectToFile(it)) }
                        )

                        SyncMethod.WEBDAV -> Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onIntent(WebDavSyncIntent.Connect) },
                            enabled = !uiState.isConnecting
                        ) {
                            if (uiState.isConnecting) {
                                ConnectingIndicator()
                            } else {
                                Text(text = stringResource(R.string.webdav_sync_connect))
                            }
                        }
                    }
                }
                Text(
                    text = when (uiState.method) {
                        SyncMethod.FILE -> stringResource(R.string.file_sync_storage_note)
                        SyncMethod.WEBDAV -> stringResource(
                            R.string.webdav_sync_storage_note,
                            "${WebDavClient.REMOTE_FOLDER_NAME}/${WebDavClient.REMOTE_FILE_NAME}"
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SyncScheduleSettings(
                    schedule = uiState.schedule,
                    onScheduleChanged = { onIntent(WebDavSyncIntent.UpdateSchedule(it)) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SyncMethodSelector(
    method: SyncMethod,
    enabled: Boolean,
    onMethodSelected: (SyncMethod) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SyncMethod.entries.forEachIndexed { index, entry ->
            SegmentedButton(
                selected = entry == method,
                onClick = { onMethodSelected(entry) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = SyncMethod.entries.size)
            ) {
                Text(
                    text = stringResource(
                        when (entry) {
                            SyncMethod.FILE -> R.string.sync_method_file
                            SyncMethod.WEBDAV -> R.string.sync_method_webdav
                        }
                    )
                )
            }
        }
    }
}

@Composable
private fun FileSyncDescription(syncFileName: String?) {
    Text(
        text = if (syncFileName != null) {
            stringResource(R.string.file_sync_connected_file, syncFileName)
        } else {
            stringResource(R.string.file_sync_description)
        },
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun FileSyncConnectButtons(
    isConnecting: Boolean,
    onFileSelected: (String) -> Unit
) {
    val createFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SYNC_FILE_MIME_TYPE)
    ) { uri -> uri?.let { onFileSelected(it.toString()) } }
    val openFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onFileSelected(it.toString()) } }

    if (isConnecting) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ConnectingIndicator()
        }
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            modifier = Modifier.weight(1f),
            // some providers, e.g. Nextcloud, don't always report JSON files as application/json
            onClick = { openFileLauncher.launch(arrayOf("*/*")) }
        ) {
            Text(text = stringResource(R.string.file_sync_choose_file))
        }
        Button(
            modifier = Modifier.weight(1f),
            onClick = { createFileLauncher.launch(WebDavClient.REMOTE_FILE_NAME) }
        ) {
            Text(text = stringResource(R.string.file_sync_create_file))
        }
    }
}

@Composable
private fun ConnectingIndicator() {
    CircularProgressIndicator(
        modifier = Modifier.size(18.dp),
        strokeWidth = 2.dp
    )
}

@Composable
private fun SyncScheduleSettings(
    schedule: SyncSchedule,
    onScheduleChanged: (SyncSchedule) -> Unit
) {
    Text(
        modifier = Modifier.padding(top = 8.dp),
        text = stringResource(R.string.sync_schedule_title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
    SyncOptionDropdown(
        title = stringResource(R.string.sync_schedule_interval),
        options = SyncSchedule.INTERVAL_OPTIONS_MINUTES,
        selectedOption = schedule.intervalMinutes,
        optionLabel = { it.asIntervalString() },
        onOptionSelected = { onScheduleChanged(schedule.copy(intervalMinutes = it)) }
    )
    SyncOptionDropdown(
        title = stringResource(R.string.sync_schedule_network),
        options = SyncNetworkCondition.entries,
        selectedOption = schedule.networkCondition,
        optionLabel = { it.asLocalString() },
        onOptionSelected = { onScheduleChanged(schedule.copy(networkCondition = it)) }
    )
    SyncOptionSwitch(
        title = stringResource(R.string.sync_schedule_battery_not_low),
        checked = schedule.requireBatteryNotLow,
        onCheckedChange = { onScheduleChanged(schedule.copy(requireBatteryNotLow = it)) }
    )
    SyncOptionSwitch(
        title = stringResource(R.string.sync_schedule_sync_on_changes),
        description = stringResource(R.string.sync_schedule_sync_on_changes_description),
        checked = schedule.syncOnChanges,
        onCheckedChange = { onScheduleChanged(schedule.copy(syncOnChanges = it)) }
    )
}

@Composable
private fun <T> SyncOptionDropdown(
    title: String,
    options: List<T>,
    selectedOption: T,
    optionLabel: @Composable (T) -> String,
    onOptionSelected: (T) -> Unit
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = true }
                .padding(vertical = 8.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = optionLabel(selectedOption),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { isExpanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = optionLabel(option)) },
                    onClick = {
                        onOptionSelected(option)
                        isExpanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun SyncOptionSwitch(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Int.asIntervalString() = when {
    this == SyncSchedule.BACKGROUND_SYNC_DISABLED -> stringResource(R.string.sync_schedule_interval_never)
    this % MINUTES_PER_HOUR == 0 -> pluralStringResource(
        R.plurals.sync_schedule_interval_hours,
        this / MINUTES_PER_HOUR,
        this / MINUTES_PER_HOUR
    )

    else -> pluralStringResource(R.plurals.sync_schedule_interval_minutes, this, this)
}

@Composable
private fun SyncNetworkCondition.asLocalString() = stringResource(
    when (this) {
        SyncNetworkCondition.ANY -> R.string.sync_network_any
        SyncNetworkCondition.UNMETERED -> R.string.sync_network_unmetered
        SyncNetworkCondition.NOT_ROAMING -> R.string.sync_network_not_roaming
        SyncNetworkCondition.NOT_REQUIRED -> R.string.sync_network_not_required
    }
)

private const val SYNC_FILE_MIME_TYPE = "application/json"
private const val MINUTES_PER_HOUR = 60

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
private fun WebDavSyncError.asLocalString() = stringResource(messageResId())

/**
 * The message that explains the error to the user.
 */
@StringRes
internal fun WebDavSyncError.messageResId(): Int = when (this) {
    WebDavSyncError.INVALID_URL -> R.string.webdav_sync_error_invalid_url
    WebDavSyncError.INSECURE_CONNECTION -> R.string.webdav_sync_error_insecure_connection
    WebDavSyncError.UNTRUSTED_CERTIFICATE -> R.string.webdav_sync_error_untrusted_certificate
    WebDavSyncError.AUTHENTICATION_FAILED -> R.string.webdav_sync_error_authentication_failed
    WebDavSyncError.NOT_FOUND -> R.string.webdav_sync_error_not_found
    WebDavSyncError.NOT_A_WEBDAV_FOLDER -> R.string.webdav_sync_error_not_a_webdav_folder
    WebDavSyncError.REDIRECT -> R.string.webdav_sync_error_redirect
    WebDavSyncError.SERVER_ERROR -> R.string.webdav_sync_error_server_error
    WebDavSyncError.NETWORK_ERROR -> R.string.webdav_sync_error_network_error
    WebDavSyncError.FILE_NOT_ACCESSIBLE -> R.string.file_sync_error_not_accessible
    WebDavSyncError.FILE_ERROR -> R.string.file_sync_error_io
    WebDavSyncError.INVALID_REMOTE_DATA -> R.string.webdav_sync_error_invalid_remote_data
    WebDavSyncError.UNSUPPORTED_REMOTE_VERSION -> R.string.webdav_sync_error_unsupported_remote_version
    WebDavSyncError.TOO_MANY_CONFLICTS -> R.string.webdav_sync_error_too_many_conflicts
    WebDavSyncError.UNKNOWN -> R.string.webdav_sync_error_unknown
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun WebDavSyncScreenPreview() {
    GroceryGeniusTheme {
        Surface {
            WebDavSyncScreen(
                uiState = WebDavSyncUiState(
                    method = SyncMethod.WEBDAV,
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