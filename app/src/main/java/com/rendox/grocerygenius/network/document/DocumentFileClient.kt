package com.rendox.grocerygenius.network.document

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncException
import com.rendox.grocerygenius.network.di.Dispatcher
import com.rendox.grocerygenius.network.di.GroceryGeniusDispatchers
import com.rendox.grocerygenius.sync.webdav.DocumentSnapshotStore
import com.rendox.grocerygenius.sync.webdav.RemoteSnapshotStore
import com.rendox.grocerygenius.sync.webdav.SyncDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FileNotFoundException
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Reads and writes the sync file picked with the system file picker. Works with any app that
 * provides files to the picker, e.g. the Nextcloud app, which uploads the changes to the server itself.
 */
class DocumentFileClient @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(GroceryGeniusDispatchers.IO) private val ioDispatcher: CoroutineDispatcher
) {
    private val contentResolver get() = context.contentResolver

    /**
     * Keeps the access to the file after the device restarts and checks that it can be read and written.
     * @throws WebDavSyncException if the file can't be used for sync
     */
    suspend fun connect(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, PERMISSION_FLAGS)
        } catch (exception: SecurityException) {
            throw WebDavSyncException(WebDavSyncError.FILE_NOT_ACCESSIBLE, cause = exception)
        }
        try {
            document(uri).readText()
            if (!supportsWrite(uri)) throw WebDavSyncException(WebDavSyncError.FILE_NOT_ACCESSIBLE)
        } catch (exception: WebDavSyncException) {
            disconnect(uri)
            throw exception
        }
    }

    fun disconnect(uri: Uri) {
        try {
            contentResolver.releasePersistableUriPermission(uri, PERMISSION_FLAGS)
        } catch (exception: SecurityException) {
            // the permission was already revoked
            Log.w(TAG, "Failed to release the permission for the sync file", exception)
        }
    }

    suspend fun displayName(uri: Uri): String? = withContext(ioDispatcher) {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (exception: Exception) {
            Log.w(TAG, "Failed to query the name of the sync file", exception)
            null
        }
    }

    /**
     * Opening the file for writing just to check it could make the provider upload it,
     * so the flags of the document are checked instead.
     */
    private suspend fun supportsWrite(uri: Uri): Boolean = execute {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0)) return@use true
                cursor.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0
            }
            // providers that don't report flags are expected to support writing
            ?: true
    }

    fun remoteSnapshotStore(uri: Uri): RemoteSnapshotStore = DocumentSnapshotStore(document(uri))

    private fun document(uri: Uri) = object : SyncDocument {
        override suspend fun readText(): String = execute {
            val inputStream = contentResolver.openInputStream(uri) ?: throw openFailedException()
            inputStream.bufferedReader().use { it.readText() }
        }

        override suspend fun writeText(content: String) = execute {
            // "wt" truncates the file, otherwise a shorter snapshot would leave the end of the old one
            val outputStream = contentResolver.openOutputStream(uri, "wt") ?: throw openFailedException()
            outputStream.bufferedWriter().use { it.write(content) }
        }
    }

    private suspend fun <T> execute(block: () -> T): T = try {
        withContext(ioDispatcher) { block() }
    } catch (exception: WebDavSyncException) {
        throw exception
    } catch (exception: Exception) {
        currentCoroutineContext().ensureActive()
        throw WebDavSyncException(
            error = when {
                // the access to the file was revoked
                exception is SecurityException -> WebDavSyncError.FILE_NOT_ACCESSIBLE
                // providers like Nextcloud and DAVx5 fail to open the file when they can't download it
                !isOnline() -> WebDavSyncError.NETWORK_ERROR
                // the file was deleted
                exception is FileNotFoundException || exception is IllegalArgumentException ->
                    WebDavSyncError.FILE_NOT_ACCESSIBLE

                exception is IOException -> WebDavSyncError.FILE_ERROR
                else -> WebDavSyncError.UNKNOWN
            },
            cause = exception
        )
    }

    /**
     * Some providers, e.g. DAVx5, return no stream instead of throwing when they can't open the file,
     * for example because they can't download it while the device is offline.
     */
    private fun openFailedException() = WebDavSyncException(
        if (isOnline()) WebDavSyncError.FILE_ERROR else WebDavSyncError.NETWORK_ERROR
    )

    private fun isOnline(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } else {
            @Suppress("DEPRECATION")
            connectivityManager.activeNetworkInfo?.isConnected == true
        }
    }

    companion object {
        private const val TAG = "DocumentFileClient"
        private const val PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}