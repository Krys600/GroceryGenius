package com.rendox.grocerygenius.sync.webdav

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LocalChangeSetTest {
    private val list = SyncGroceryList(id = "list", name = "Groceries", sortingPriority = 0)
    private val milk = SyncGrocery(
        productId = "milk",
        groceryListId = "list",
        description = null,
        purchased = false,
        purchasedLastModified = 0
    )
    private val localSnapshot = SyncSnapshot(
        groceryLists = listOf(list),
        groceries = listOf(milk),
        defaultProducts = predefinedDefaultProducts,
        categories = predefinedCategories
    )
    private val local = LocalSyncData(localSnapshot, predefinedReferences)

    @Test
    fun `is empty when nothing changed`() {
        assertThat(LocalChangeSet.between(local, localSnapshot).isEmpty).isTrue()
    }

    @Test
    fun `contains only the records that changed`() {
        val cake = SyncCustomProduct(id = "cake", name = "Cake", iconId = "bread.svg", categoryId = "bakery")
        val target = localSnapshot.copy(
            groceryLists = listOf(list.copy(name = "Weekly"), list.copy(id = "new")),
            groceries = listOf(milk, milk.copy(productId = "cake")),
            customProducts = listOf(cake),
            categories = predefinedCategories.map {
                if (it.id == "dairy") it.copy(sortingPriority = -1) else it
            }
        )

        val changes = LocalChangeSet.between(local, target)

        assertThat(changes).isEqualTo(
            LocalChangeSet(
                groceryListsToUpsert = listOf(list.copy(name = "Weekly"), list.copy(id = "new")),
                customProductsToUpsert = listOf(cake),
                categoriesToUpdate = listOf(SyncCategory("dairy", sortingPriority = -1)),
                groceriesToUpsert = listOf(milk.copy(productId = "cake"))
            )
        )
    }

    @Test
    fun `deletes records missing from the target`() {
        val changes = LocalChangeSet.between(
            local,
            localSnapshot.copy(groceryLists = emptyList(), groceries = emptyList())
        )

        assertThat(changes.groceriesToDelete).containsExactly(milk.key)
        assertThat(changes.groceryListIdsToDelete).containsExactly("list")
    }

    @Test
    fun `skips data that references predefined data missing locally`() {
        val target = localSnapshot.copy(
            groceries = listOf(milk, milk.copy(productId = "unknown")),
            customProducts = listOf(SyncCustomProduct("cake", "Cake", iconId = "cake.svg", categoryId = "unknown")),
            defaultProducts = predefinedDefaultProducts + SyncDefaultProduct("unknown", null, null),
            categories = predefinedCategories + SyncCategory("unknown", 0)
        )

        val changes = LocalChangeSet.between(local, target)

        assertThat(changes).isEqualTo(
            LocalChangeSet(customProductsToUpsert = listOf(SyncCustomProduct("cake", "Cake", null, null)))
        )
    }
}