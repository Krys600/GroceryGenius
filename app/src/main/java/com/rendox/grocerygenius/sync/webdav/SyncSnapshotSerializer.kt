package com.rendox.grocerygenius.sync.webdav

import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncException
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import java.io.IOException
import javax.inject.Inject

class SyncSnapshotSerializer @Inject constructor(moshi: Moshi) {
    private val snapshotAdapter = moshi.adapter(SyncSnapshot::class.java).indent("  ")
    private val baseAdapter = moshi.adapter(SyncBase::class.java)

    fun encodeSnapshot(snapshot: SyncSnapshot): String = snapshotAdapter.toJson(snapshot)

    /**
     * Parses the snapshot file downloaded from the server.
     * @throws WebDavSyncException if the file is malformed or was written by a newer app version
     */
    fun decodeSnapshot(json: String): SyncSnapshot {
        val snapshot = try {
            snapshotAdapter.fromJson(json)
        } catch (exception: IOException) {
            throw WebDavSyncException(WebDavSyncError.INVALID_REMOTE_DATA, cause = exception)
        } catch (exception: JsonDataException) {
            throw WebDavSyncException(WebDavSyncError.INVALID_REMOTE_DATA, cause = exception)
        } ?: throw WebDavSyncException(WebDavSyncError.INVALID_REMOTE_DATA)

        if (snapshot.formatVersion > SYNC_SNAPSHOT_FORMAT_VERSION) {
            throw WebDavSyncException(WebDavSyncError.UNSUPPORTED_REMOTE_VERSION)
        }
        return snapshot.copy(formatVersion = SYNC_SNAPSHOT_FORMAT_VERSION).canonical()
    }

    fun encodeBase(base: SyncBase): String = baseAdapter.toJson(base)

    /**
     * Returns null if the base can't be read, in which case the next sync starts from scratch.
     */
    fun decodeBase(json: String): SyncBase? = try {
        baseAdapter.fromJson(json)?.let { base ->
            base.copy(snapshot = base.snapshot.canonical())
        }
    } catch (exception: IOException) {
        null
    } catch (exception: JsonDataException) {
        null
    }
}