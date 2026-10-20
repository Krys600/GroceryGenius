package com.rendox.grocerygenius.sync.webdav

import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncException
import javax.inject.Inject

interface LocalSyncStore {
    suspend fun read(): LocalSyncData

    /**
     * Atomically reads the local data, computes the target snapshot with [transform] and
     * applies it to the local database. Returns the target snapshot together with the local
     * references it was applied against.
     */
    suspend fun update(transform: (LocalSyncData) -> SyncSnapshot): LocalSyncData
}

interface SyncBaseStore {
    suspend fun read(): SyncBase?
    suspend fun write(base: SyncBase)
    suspend fun clear()
}

data class RemoteSnapshotFile(
    val content: String,
    val etag: String?
)

enum class UploadResult {
    UPLOADED,

    /**
     * The file was changed on the server since it was downloaded.
     */
    PRECONDITION_FAILED
}

interface RemoteSnapshotStore {
    /**
     * Returns null if the file doesn't exist yet.
     */
    suspend fun download(): RemoteSnapshotFile?

    /**
     * Uploads the file, but only if it wasn't changed since [replacing] was downloaded,
     * or, if [replacing] is null, only if the file still doesn't exist.
     */
    suspend fun upload(
        content: String,
        replacing: RemoteSnapshotFile?
    ): UploadResult
}

/**
 * Synchronizes the local database with the snapshot file on the server.
 *
 * Each round downloads the remote snapshot, merges it with the local data using the base from the
 * previous sync, applies the result locally and uploads it. The upload is conditional, so if another
 * device uploaded its changes in the meantime, the next round merges them too.
 */
class WebDavSyncEngine @Inject constructor(
    private val localStore: LocalSyncStore,
    private val baseStore: SyncBaseStore,
    private val serializer: SyncSnapshotSerializer
) {
    suspend fun sync(remoteStore: RemoteSnapshotStore) {
        var base = baseStore.read() ?: SyncBase()
        repeat(MAX_ROUNDS) {
            val remoteFile = remoteStore.download()
            val remote = remoteFile?.let { serializer.decodeSnapshot(it.content) } ?: SyncSnapshot()
            val applied = localStore.update { local ->
                SnapshotMerger.merge(
                    base = base.snapshot,
                    local = SnapshotMerger.restoreUnavailableRecords(local, base),
                    remote = remote
                )
            }
            val merged = applied.snapshot

            // The local data now includes everything from the remote snapshot, so the remote
            // snapshot is the common ancestor until the merged snapshot gets uploaded.
            base = SyncBase(snapshot = remote, references = applied.references)
            baseStore.write(base)

            if (remoteFile == null || merged != remote) {
                val content = serializer.encodeSnapshot(merged)
                when (remoteStore.upload(content, replacing = remoteFile)) {
                    // another device uploaded its changes in the meantime, merge them in the next round
                    UploadResult.PRECONDITION_FAILED -> return@repeat
                    UploadResult.UPLOADED -> {
                        base = SyncBase(snapshot = merged, references = applied.references)
                        baseStore.write(base)
                    }
                }
            }

            // the user could have changed something while the sync was running
            val local = localStore.read()
            if (SnapshotMerger.restoreUnavailableRecords(local, base) == base.snapshot) return
        }
        throw WebDavSyncException(WebDavSyncError.TOO_MANY_CONFLICTS)
    }

    companion object {
        private const val MAX_ROUNDS = 5
    }
}