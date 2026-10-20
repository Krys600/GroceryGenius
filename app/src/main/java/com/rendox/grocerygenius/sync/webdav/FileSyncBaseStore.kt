package com.rendox.grocerygenius.sync.webdav

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Stores the [SyncBase] as a JSON file in the app's private storage.
 */
class FileSyncBaseStore(
    private val file: File,
    private val serializer: SyncSnapshotSerializer,
    private val ioDispatcher: CoroutineDispatcher
) : SyncBaseStore {
    override suspend fun read(): SyncBase? = withContext(ioDispatcher) {
        if (file.exists()) serializer.decodeBase(file.readText()) else null
    }

    override suspend fun write(base: SyncBase) = withContext(ioDispatcher) {
        file.parentFile?.mkdirs()
        // write to a temporary file first so that the base is never left half written
        val temporaryFile = File(file.path + ".tmp")
        temporaryFile.writeText(serializer.encodeBase(base))
        if (!temporaryFile.renameTo(file)) {
            throw IOException("Failed to save the sync base to ${file.path}")
        }
    }

    override suspend fun clear() {
        withContext(ioDispatcher) {
            file.delete()
        }
    }
}