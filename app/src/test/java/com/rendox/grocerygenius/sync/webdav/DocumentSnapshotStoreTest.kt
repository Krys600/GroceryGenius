package com.rendox.grocerygenius.sync.webdav

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DocumentSnapshotStoreTest {
    private lateinit var document: FakeSyncDocument
    private lateinit var store: DocumentSnapshotStore

    @BeforeEach
    fun setUp() {
        document = FakeSyncDocument()
        store = DocumentSnapshotStore(document)
    }

    @Test
    fun `an empty file is treated as missing`() = runBlocking {
        document.content = "  \n"

        assertThat(store.download()).isNull()
    }

    @Test
    fun `the first upload fills an empty file`() = runBlocking {
        val result = store.upload("snapshot", replacing = null)

        assertThat(result).isEqualTo(UploadResult.UPLOADED)
        assertThat(document.content).isEqualTo("snapshot")
    }

    @Test
    fun `the first upload doesn't overwrite a file filled in the meantime`() = runBlocking {
        document.content = "other device"

        val result = store.upload("snapshot", replacing = null)

        assertThat(result).isEqualTo(UploadResult.PRECONDITION_FAILED)
        assertThat(document.content).isEqualTo("other device")
    }

    @Test
    fun `an upload doesn't overwrite a file changed since it was downloaded`() = runBlocking {
        document.content = "version 1"
        val downloaded = store.download()
        document.content = "version 2"

        val result = store.upload("version 3", replacing = downloaded)

        assertThat(result).isEqualTo(UploadResult.PRECONDITION_FAILED)
        assertThat(document.content).isEqualTo("version 2")
    }

    @Test
    fun `an upload replaces an unchanged file`() = runBlocking {
        document.content = "version 1"
        val downloaded = store.download()

        val result = store.upload("version 2", replacing = downloaded)

        assertThat(result).isEqualTo(UploadResult.UPLOADED)
        assertThat(document.content).isEqualTo("version 2")
    }

    @Test
    fun `concurrent changes of two devices are merged through the file`() = runBlocking {
        val phone = FakeDevice()
        val tablet = FakeDevice()
        phone.local.addGroceryList("weekly")
        phone.local.addGrocery("milk", "weekly")
        phone.syncWith(store)
        tablet.syncWith(store)

        phone.local.addGrocery("bread", "weekly")
        tablet.local.addGrocery("apple", "weekly")
        // the tablet writes the file after the phone downloaded it, but before the phone uploads
        var tabletSynced = false
        val phoneStore = object : RemoteSnapshotStore by store {
            override suspend fun upload(
                content: String,
                replacing: RemoteSnapshotFile?
            ): UploadResult {
                if (!tabletSynced) {
                    tabletSynced = true
                    tablet.syncWith(store)
                }
                return store.upload(content, replacing)
            }
        }
        phone.syncWith(phoneStore)
        tablet.syncWith(store)

        val expected = listOf("apple", "bread", "milk")
        assertThat(phone.local.groceries().map { it.productId }).isEqualTo(expected)
        assertThat(tablet.local.groceries().map { it.productId }).isEqualTo(expected)
    }
}

private class FakeSyncDocument : SyncDocument {
    var content = ""

    override suspend fun readText() = content

    override suspend fun writeText(content: String) {
        this.content = content
    }
}