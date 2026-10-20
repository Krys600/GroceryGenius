package com.rendox.grocerygenius.sync.webdav

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SnapshotMergerTest {
    private val list = SyncGroceryList(id = "list", name = "Groceries", sortingPriority = 0)
    private val milk = SyncGrocery(
        productId = "milk",
        groceryListId = "list",
        description = null,
        purchased = false,
        purchasedLastModified = 0
    )
    private val base = SyncSnapshot(groceryLists = listOf(list), groceries = listOf(milk))

    @Test
    fun `takes the changes of the side that changed a record`() {
        val local = base.copy(groceryLists = listOf(list.copy(name = "Weekly")))
        val remote = base.copy(groceries = listOf(milk.copy(description = "2 liters")))

        val merged = SnapshotMerger.merge(base, local, remote)

        assertThat(merged.groceryLists).containsExactly(list.copy(name = "Weekly"))
        assertThat(merged.groceries).containsExactly(milk.copy(description = "2 liters"))
    }

    @Test
    fun `local value wins when both sides changed the same field`() {
        val local = base.copy(groceryLists = listOf(list.copy(name = "Local")))
        val remote = base.copy(groceryLists = listOf(list.copy(name = "Remote", sortingPriority = 5)))

        val merged = SnapshotMerger.merge(base, local, remote)

        assertThat(merged.groceryLists).containsExactly(list.copy(name = "Local", sortingPriority = 5))
    }

    @Test
    fun `remote value wins for records that are new on both sides`() {
        val local = SyncSnapshot(categories = listOf(SyncCategory("dairy", sortingPriority = 1)))
        val remote = SyncSnapshot(categories = listOf(SyncCategory("dairy", sortingPriority = 7)))

        val merged = SnapshotMerger.merge(SyncSnapshot(), local, remote)

        assertThat(merged.categories).containsExactly(SyncCategory("dairy", sortingPriority = 7))
    }

    @Test
    fun `newer purchase state wins regardless of the side`() {
        val local = base.copy(groceries = listOf(milk.copy(purchased = true, purchasedLastModified = 5)))
        val remote = base.copy(groceries = listOf(milk.copy(purchased = false, purchasedLastModified = 9)))

        val merged = SnapshotMerger.merge(base, local, remote)

        assertThat(merged.groceries.single().purchased).isFalse()
        assertThat(merged.groceries.single().purchasedLastModified).isEqualTo(9)
    }

    @Test
    fun `deletions are applied unless the other side modified the record`() {
        val bread = milk.copy(productId = "bread")
        val baseWithBread = base.copy(groceries = listOf(milk, bread))
        val local = baseWithBread.copy(groceries = listOf(bread.copy(description = "whole grain")))
        val remote = baseWithBread.copy(groceries = listOf(milk))

        val merged = SnapshotMerger.merge(baseWithBread, local, remote)

        assertThat(merged.groceries).containsExactly(bread.copy(description = "whole grain"))
    }

    @Test
    fun `drops groceries of deleted lists and custom products`() {
        val cake = SyncCustomProduct(id = "cake", name = "Cake", iconId = null, categoryId = null)
        val cakeGrocery = milk.copy(productId = "cake")
        val otherList = list.copy(id = "other")
        val base = SyncSnapshot(groceryLists = listOf(list, otherList), customProducts = listOf(cake))
        val local = base.copy(customProducts = emptyList())
        val remote = base.copy(
            groceryLists = listOf(list),
            groceries = listOf(cakeGrocery, milk.copy(groceryListId = "other"), milk)
        )

        val merged = SnapshotMerger.merge(base, local, remote)

        assertThat(merged.groceries).containsExactly(milk)
    }

    @Test
    fun `restores groceries of default products that are missing locally`() {
        val base = SyncBase(snapshot = this.base, references = predefinedReferences)
        val local = LocalSyncData(
            snapshot = SyncSnapshot(groceryLists = listOf(list)),
            references = predefinedReferences.copy(defaultProductIds = emptySet())
        )

        val restored = SnapshotMerger.restoreUnavailableRecords(local, base)

        assertThat(restored.groceries).containsExactly(milk)
    }

    @Test
    fun `does not restore groceries the user removed`() {
        val base = SyncBase(snapshot = this.base, references = predefinedReferences)
        val local = LocalSyncData(
            snapshot = SyncSnapshot(groceryLists = listOf(list)),
            references = predefinedReferences
        )

        val restored = SnapshotMerger.restoreUnavailableRecords(local, base)

        assertThat(restored.groceries).isEmpty()
    }

    @Test
    fun `restores references to icons that are missing locally`() {
        val cake = SyncCustomProduct(id = "cake", name = "Cake", iconId = "cake.svg", categoryId = "bakery")
        val base = SyncBase(
            snapshot = SyncSnapshot(customProducts = listOf(cake)),
            references = predefinedReferences
        )
        val local = LocalSyncData(
            snapshot = SyncSnapshot(customProducts = listOf(cake.copy(iconId = null, name = "Cheesecake"))),
            references = predefinedReferences
        )

        val restored = SnapshotMerger.restoreUnavailableRecords(local, base)

        assertThat(restored.customProducts).containsExactly(cake.copy(name = "Cheesecake"))
    }
}