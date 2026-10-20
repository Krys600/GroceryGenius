package com.rendox.grocerygenius.sync.webdav

import com.google.common.truth.Truth.assertThat
import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncException
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WebDavSyncEngineTest {
    private lateinit var remote: FakeRemoteSnapshotStore
    private lateinit var phone: FakeDevice
    private lateinit var tablet: FakeDevice

    @BeforeEach
    fun setUp() {
        remote = FakeRemoteSnapshotStore()
        phone = FakeDevice()
        tablet = FakeDevice()
    }

    @Test
    fun `first sync uploads the local data`() = runBlocking {
        phone.local.addGroceryList("weekly")
        phone.local.addGrocery("milk", "weekly")

        phone.syncWith(remote)

        assertThat(remote.snapshot).isEqualTo(phone.local.snapshot.canonical())
        assertThat(phone.baseStore.base?.snapshot).isEqualTo(remote.snapshot)
    }

    @Test
    fun `a new device gets the existing data and keeps its own`() = runBlocking {
        phone.local.addGroceryList("weekly")
        phone.local.addGrocery("milk", "weekly")
        phone.syncWith(remote)
        tablet.local.addGroceryList("party")
        tablet.local.addGrocery("bread", "party")

        tablet.syncWith(remote)
        phone.syncWith(remote)

        val expectedLists = listOf(SyncGroceryList("party", "party", 0), SyncGroceryList("weekly", "weekly", 0))
        assertThat(tablet.local.groceryLists()).isEqualTo(expectedLists)
        assertThat(phone.local.groceryLists()).isEqualTo(expectedLists)
        assertThat(phone.local.groceries()).isEqualTo(tablet.local.groceries())
        assertThat(phone.local.groceries().map { it.productId }).containsExactly("bread", "milk")
    }

    @Test
    fun `a new device adopts the customizations from the server`() = runBlocking {
        phone.local.updateCategoryPriority("dairy", -1)
        phone.local.updateDefaultProduct("apple", iconId = "bread.svg", categoryId = "bakery")
        phone.syncWith(remote)

        tablet.syncWith(remote)

        assertThat(tablet.local.snapshot.categories.single { it.id == "dairy" }.sortingPriority).isEqualTo(-1)
        assertThat(tablet.local.snapshot.defaultProducts.single { it.id == "apple" })
            .isEqualTo(SyncDefaultProduct("apple", iconId = "bread.svg", categoryId = "bakery"))
    }

    @Test
    fun `changes and deletions propagate to the other device`() = runBlocking {
        phone.local.addGroceryList("weekly")
        phone.local.addGroceryList("party")
        phone.local.addCustomProduct("cake", iconId = "bread.svg", categoryId = "bakery")
        phone.local.addGrocery("cake", "party")
        phone.local.addGrocery("milk", "weekly")
        phone.syncWith(remote)
        tablet.syncWith(remote)

        tablet.local.renameGroceryList("weekly", "Weekly shopping")
        tablet.local.updateGrocery("milk", "weekly") { it.copy(description = "2 liters") }
        tablet.local.deleteGroceryList("party")
        tablet.local.deleteCustomProduct("cake")
        tablet.syncWith(remote)
        phone.syncWith(remote)

        assertThat(phone.local.snapshot.canonical()).isEqualTo(tablet.local.snapshot.canonical())
        assertThat(phone.local.groceryLists()).containsExactly(SyncGroceryList("weekly", "Weekly shopping", 0))
        assertThat(phone.local.groceries().single().description).isEqualTo("2 liters")
        assertThat(phone.local.snapshot.customProducts).isEmpty()
    }

    @Test
    fun `concurrent changes of different fields are both kept`() = runBlocking {
        phone.local.addGroceryList("weekly")
        phone.local.addGrocery("milk", "weekly")
        phone.syncWith(remote)
        tablet.syncWith(remote)

        phone.local.updateGrocery("milk", "weekly") { it.copy(description = "lactose free") }
        tablet.local.updateGrocery("milk", "weekly") { it.copy(purchased = true, purchasedLastModified = 10) }
        tablet.local.addGrocery("apple", "weekly")
        phone.syncWith(remote)
        tablet.syncWith(remote)
        phone.syncWith(remote)

        val expected = listOf(
            SyncGrocery("apple", "weekly", null, purchased = false, purchasedLastModified = 0),
            SyncGrocery("milk", "weekly", "lactose free", purchased = true, purchasedLastModified = 10)
        )
        assertThat(phone.local.groceries()).isEqualTo(expected)
        assertThat(tablet.local.groceries()).isEqualTo(expected)
    }

    @Test
    fun `the most recent purchase state wins`() = runBlocking {
        phone.local.addGroceryList("weekly")
        phone.local.addGrocery("milk", "weekly")
        phone.syncWith(remote)
        tablet.syncWith(remote)

        tablet.local.updateGrocery("milk", "weekly") { it.copy(purchased = true, purchasedLastModified = 20) }
        phone.local.updateGrocery("milk", "weekly") { it.copy(purchased = true, purchasedLastModified = 10) }
        phone.local.updateGrocery("milk", "weekly") { it.copy(purchased = false, purchasedLastModified = 30) }
        tablet.syncWith(remote)
        phone.syncWith(remote)
        tablet.syncWith(remote)

        assertThat(phone.local.groceries().single().purchased).isFalse()
        assertThat(tablet.local.groceries().single().purchased).isFalse()
    }

    @Test
    fun `an item modified on one device and deleted on the other is kept`() = runBlocking {
        phone.local.addGroceryList("weekly")
        phone.local.addGrocery("milk", "weekly")
        phone.syncWith(remote)
        tablet.syncWith(remote)

        phone.local.removeGrocery("milk", "weekly")
        tablet.local.updateGrocery("milk", "weekly") { it.copy(description = "2 liters") }
        phone.syncWith(remote)
        tablet.syncWith(remote)
        phone.syncWith(remote)

        assertThat(phone.local.groceries().single().description).isEqualTo("2 liters")
        assertThat(tablet.local.groceries()).isEqualTo(phone.local.groceries())
    }

    @Test
    fun `a grocery added to a list that was deleted on the other device is dropped`() = runBlocking {
        phone.local.addGroceryList("weekly")
        phone.syncWith(remote)
        tablet.syncWith(remote)

        phone.local.deleteGroceryList("weekly")
        tablet.local.addGrocery("milk", "weekly")
        phone.syncWith(remote)
        tablet.syncWith(remote)

        assertThat(tablet.local.groceryLists()).isEmpty()
        assertThat(tablet.local.groceries()).isEmpty()
        assertThat(remote.snapshot?.groceries).isEmpty()
    }

    @Test
    fun `merges changes that another device uploaded during the sync`() = runBlocking {
        phone.local.addGroceryList("weekly")
        phone.syncWith(remote)
        tablet.syncWith(remote)

        phone.local.addGrocery("milk", "weekly")
        tablet.local.addGrocery("bread", "weekly")
        remote.beforeUpload = { tablet.syncWith(remote) }
        phone.syncWith(remote)
        tablet.syncWith(remote)

        assertThat(remote.snapshot?.groceries?.map { it.productId }).containsExactly("bread", "milk")
        assertThat(phone.local.groceries()).isEqualTo(remote.snapshot?.groceries)
        assertThat(tablet.local.groceries()).isEqualTo(remote.snapshot?.groceries)
    }

    @Test
    fun `does not upload when nothing changed`() = runBlocking {
        phone.local.addGroceryList("weekly")
        phone.syncWith(remote)
        tablet.syncWith(remote)
        val uploads = remote.uploadCount

        phone.syncWith(remote)
        tablet.syncWith(remote)

        assertThat(remote.uploadCount).isEqualTo(uploads)
    }

    @Test
    fun `a failed upload does not revert local changes on the next sync`() = runBlocking {
        phone.local.addGroceryList("weekly")
        phone.syncWith(remote)
        tablet.syncWith(remote)

        tablet.local.addGrocery("bread", "weekly")
        tablet.syncWith(remote)
        phone.local.addGrocery("milk", "weekly")
        remote.failUploads = true
        assertThrows(IOException::class.java) { runBlocking { phone.syncWith(remote) } }
        assertThat(phone.local.groceries().map { it.productId }).containsExactly("bread", "milk")

        tablet.local.addGrocery("apple", "weekly")
        remote.failUploads = false
        tablet.syncWith(remote)
        phone.syncWith(remote)
        tablet.syncWith(remote)

        val expected = listOf("apple", "bread", "milk")
        assertThat(phone.local.groceries().map { it.productId }).containsExactlyElementsIn(expected)
        assertThat(tablet.local.groceries().map { it.productId }).containsExactlyElementsIn(expected)
    }

    @Test
    fun `syncs changes that the user made while the sync was running`() = runBlocking {
        phone.local.addGroceryList("weekly")
        phone.local.onUpdateApplied = {
            phone.local.onUpdateApplied = null
            phone.local.addGrocery("milk", "weekly")
        }

        phone.syncWith(remote)

        assertThat(remote.snapshot?.groceries?.map { it.productId }).containsExactly("milk")
    }

    @Test
    fun `a device without predefined data keeps groceries it can't show yet`() = runBlocking {
        phone.local.addGroceryList("weekly")
        phone.local.addGrocery("milk", "weekly")
        phone.local.updateDefaultProduct("milk", iconId = "bread.svg", categoryId = "bakery")
        phone.local.updateCategoryPriority("dairy", -1)
        phone.syncWith(remote)

        // the tablet skipped downloading predefined groceries during onboarding
        val offlineTablet = FakeDevice(FakeLocalSyncStore(references = LocalReferences()))
        offlineTablet.syncWith(remote)
        offlineTablet.local.addCustomProduct("custom")
        offlineTablet.local.addGrocery("custom", "weekly")
        offlineTablet.syncWith(remote)

        assertThat(offlineTablet.local.groceryLists().map { it.id }).containsExactly("weekly")
        assertThat(offlineTablet.local.groceries().map { it.productId }).containsExactly("custom")
        val remoteSnapshot = remote.snapshot!!
        assertThat(remoteSnapshot.groceries.map { it.productId }).containsExactly("custom", "milk")
        assertThat(remoteSnapshot.defaultProducts.single { it.id == "milk" }.categoryId).isEqualTo("bakery")

        // once the predefined data is downloaded, the missing groceries and customizations get applied
        offlineTablet.local.downloadPredefinedData()
        offlineTablet.syncWith(remote)

        assertThat(offlineTablet.local.groceries().map { it.productId }).containsExactly("custom", "milk")
        assertThat(offlineTablet.local.snapshot.defaultProducts.single { it.id == "milk" }.categoryId)
            .isEqualTo("bakery")
        assertThat(offlineTablet.local.snapshot.categories.single { it.id == "dairy" }.sortingPriority)
            .isEqualTo(-1)
        assertThat(remote.snapshot).isEqualTo(remoteSnapshot)
    }

    @Test
    fun `references to missing icons are restored instead of being cleared on the server`() = runBlocking {
        phone.local.addCustomProduct("cake", iconId = "bread.svg", categoryId = "bakery")
        phone.syncWith(remote)

        val tabletWithoutIcons = FakeDevice(
            FakeLocalSyncStore(references = predefinedReferences.copy(iconIds = emptySet()))
        )
        tabletWithoutIcons.syncWith(remote)
        tabletWithoutIcons.syncWith(remote)

        assertThat(tabletWithoutIcons.local.snapshot.customProducts.single().iconId).isNull()
        assertThat(remote.snapshot?.customProducts?.single()?.iconId).isEqualTo("bread.svg")
    }

    @Test
    fun `rejects remote data from a newer app version`() {
        remote.file = RemoteSnapshotFile(content = """{"formatVersion": 99}""", etag = "\"1\"")

        val exception = assertThrows(WebDavSyncException::class.java) {
            runBlocking { phone.syncWith(remote) }
        }

        assertThat(exception.error).isEqualTo(WebDavSyncError.UNSUPPORTED_REMOTE_VERSION)
        assertThat(remote.uploadCount).isEqualTo(0)
    }

    @Test
    fun `rejects malformed remote data without overwriting it`() {
        remote.file = RemoteSnapshotFile(content = "<html>Login</html>", etag = "\"1\"")

        val exception = assertThrows(WebDavSyncException::class.java) {
            runBlocking { phone.syncWith(remote) }
        }

        assertThat(exception.error).isEqualTo(WebDavSyncError.INVALID_REMOTE_DATA)
        assertThat(remote.uploadCount).isEqualTo(0)
    }
}