package com.rendox.grocerygenius.feature.webdavsync

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rendox.grocerygenius.R
import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncStatus
import com.rendox.grocerygenius.ui.helpers.ObserveUiEvent
import com.rendox.grocerygenius.ui.theme.GroceryGeniusTheme

/**
 * A toolbar button that spins while the data is being synced, turns into a red warning icon if the
 * last sync failed, and syncs when clicked. Hidden while sync is disabled.
 */
@Composable
fun SyncButton(
    modifier: Modifier = Modifier,
    viewModel: SyncButtonViewModel = hiltViewModel()
) {
    val status by viewModel.syncStatusFlow.collectAsStateWithLifecycle()
    val syncFailedEvent by viewModel.syncFailedEvent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    ObserveUiEvent(syncFailedEvent) { error ->
        val message = context.getString(
            R.string.sync_button_failed_message,
            context.getString(error.messageResId())
        )
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    status?.let {
        SyncButton(
            modifier = modifier,
            status = it,
            onClick = viewModel::syncNow
        )
    }
}

@Composable
private fun SyncButton(
    modifier: Modifier = Modifier,
    status: WebDavSyncStatus,
    onClick: () -> Unit
) {
    val lastSyncFailed = status.lastError != null && !status.isSyncing
    IconButton(
        modifier = modifier,
        onClick = onClick
    ) {
        Icon(
            modifier = if (status.isSyncing) Modifier.rotate(syncingRotation()) else Modifier,
            painter = painterResource(
                if (lastSyncFailed) R.drawable.baseline_sync_problem_24 else R.drawable.baseline_sync_24
            ),
            tint = if (lastSyncFailed) MaterialTheme.colorScheme.error else LocalContentColor.current,
            contentDescription = when {
                status.isSyncing -> stringResource(R.string.webdav_sync_status_syncing)
                status.lastError != null -> stringResource(
                    R.string.sync_button_failed_content_description,
                    stringResource(status.lastError.messageResId())
                )

                else -> stringResource(R.string.webdav_sync_sync_now)
            }
        )
    }
}

@Composable
private fun syncingRotation(): Float {
    val transition = rememberInfiniteTransition(label = "SyncButtonTransition")
    val rotation by transition.animateFloat(
        initialValue = 0F,
        targetValue = -360F,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SyncButtonRotation"
    )
    return rotation
}

@Preview
@Composable
private fun SyncButtonFailedPreview() {
    GroceryGeniusTheme {
        Surface {
            SyncButton(
                status = WebDavSyncStatus(lastError = WebDavSyncError.NETWORK_ERROR),
                onClick = {}
            )
        }
    }
}