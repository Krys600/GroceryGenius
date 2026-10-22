package com.rendox.grocerygenius.sync.webdav

/**
 * A single file that can be read and overwritten, e.g. a document picked with the system file picker.
 */
interface SyncDocument {
    /**
     * Returns an empty string if the file is empty, e.g. right after it was created.
     */
    suspend fun readText(): String
    suspend fun writeText(content: String)
}

/**
 * Stores the sync snapshot in a [SyncDocument].
 *
 * Documents don't support conditional uploads, so before overwriting the file, its content is read
 * again and compared with the downloaded one. If another device changed it in the meantime, the upload
 * is skipped and the engine merges the changes in the next round. Only the short time between the
 * check and the write is left unprotected.
 */
class DocumentSnapshotStore(
    private val document: SyncDocument
) : RemoteSnapshotStore {
    override suspend fun download(): RemoteSnapshotFile? {
        val content = document.readText()
        return if (content.isBlank()) null else RemoteSnapshotFile(content = content, etag = null)
    }

    override suspend fun upload(
        content: String,
        replacing: RemoteSnapshotFile?
    ): UploadResult {
        val currentContent = document.readText()
        val isUnchanged = if (replacing == null) {
            currentContent.isBlank()
        } else {
            currentContent == replacing.content
        }
        if (!isUnchanged) return UploadResult.PRECONDITION_FAILED
        document.writeText(content)
        return UploadResult.UPLOADED
    }
}