package com.rendox.grocerygenius.sync.webdav

import com.google.common.truth.Truth.assertThat
import com.rendox.grocerygenius.model.WebDavSyncError
import com.rendox.grocerygenius.model.WebDavSyncException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SyncSnapshotSerializerTest {
    private val snapshot = SyncSnapshot(
        groceryLists = listOf(SyncGroceryList("b", "Party", 2), SyncGroceryList("a", "Weekly", 1)),
        groceries = listOf(
            SyncGrocery("milk", "a", description = "2 liters", purchased = true, purchasedLastModified = 3),
            SyncGrocery("bread", "a", description = null, purchased = false, purchasedLastModified = 4)
        ),
        customProducts = listOf(SyncCustomProduct("cake", "Cake", iconId = null, categoryId = "bakery")),
        defaultProducts = listOf(SyncDefaultProduct("milk", iconId = "milk.svg", categoryId = null)),
        categories = listOf(SyncCategory("dairy", 5))
    )

    @Test
    fun `round trip returns the canonical snapshot`() {
        val decoded = testSerializer.decodeSnapshot(testSerializer.encodeSnapshot(snapshot))

        assertThat(decoded).isEqualTo(snapshot.canonical())
    }

    @Test
    fun `ignores unknown properties and uses defaults for missing ones`() {
        val json = """
            {
              "formatVersion": 1,
              "groceryLists": [{"id": "a", "name": "Weekly", "sortingPriority": 1, "color": "red"}],
              "groceries": [{"productId": "milk", "groceryListId": "a", "purchased": false, "purchasedLastModified": 0}]
            }
        """.trimIndent()

        val decoded = testSerializer.decodeSnapshot(json)

        assertThat(decoded).isEqualTo(
            SyncSnapshot(
                groceryLists = listOf(SyncGroceryList("a", "Weekly", 1)),
                groceries = listOf(SyncGrocery("milk", "a", null, false, 0))
            )
        )
    }

    @Test
    fun `rejects snapshots of a newer format`() {
        val exception = assertThrows(WebDavSyncException::class.java) {
            testSerializer.decodeSnapshot("""{"formatVersion": ${SYNC_SNAPSHOT_FORMAT_VERSION + 1}}""")
        }
        assertThat(exception.error).isEqualTo(WebDavSyncError.UNSUPPORTED_REMOTE_VERSION)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "null", "<html></html>", "{\"groceryLists\": [{\"id\": 1}]}"])
    fun `rejects malformed snapshots`(json: String) {
        val exception = assertThrows(WebDavSyncException::class.java) {
            testSerializer.decodeSnapshot(json)
        }
        assertThat(exception.error).isEqualTo(WebDavSyncError.INVALID_REMOTE_DATA)
    }

    @Test
    fun `base round trip keeps the references`() {
        val base = SyncBase(snapshot.canonical(), predefinedReferences)

        assertThat(testSerializer.decodeBase(testSerializer.encodeBase(base))).isEqualTo(base)
    }

    @Test
    fun `unreadable base is treated as missing`() {
        assertThat(testSerializer.decodeBase("corrupted")).isNull()
    }
}