package com.rendox.grocerygenius.sync.webdav

import com.squareup.moshi.JsonClass

const val SYNC_SNAPSHOT_FORMAT_VERSION = 1

/**
 * The user data that is shared between devices through the WebDAV server. It is stored on the
 * server as a single JSON file.
 *
 * Predefined data (icons, categories and default products) is fetched by every device from GitHub,
 * so only the parts of it that the user can customize are included: the category order and the
 * icon and category of default products.
 */
@JsonClass(generateAdapter = true)
data class SyncSnapshot(
    val formatVersion: Int = SYNC_SNAPSHOT_FORMAT_VERSION,
    val groceryLists: List<SyncGroceryList> = emptyList(),
    val groceries: List<SyncGrocery> = emptyList(),
    val customProducts: List<SyncCustomProduct> = emptyList(),
    val defaultProducts: List<SyncDefaultProduct> = emptyList(),
    val categories: List<SyncCategory> = emptyList()
) {
    /**
     * Returns the snapshot with all records sorted by their keys, so that two snapshots
     * with the same content are equal regardless of the order the records were read in.
     */
    fun canonical() = copy(
        groceryLists = groceryLists.sortedBy { it.id },
        groceries = groceries.sortedWith(compareBy({ it.groceryListId }, { it.productId })),
        customProducts = customProducts.sortedBy { it.id },
        defaultProducts = defaultProducts.sortedBy { it.id },
        categories = categories.sortedBy { it.id }
    )
}

@JsonClass(generateAdapter = true)
data class SyncGroceryList(
    val id: String,
    val name: String,
    val sortingPriority: Long
)

@JsonClass(generateAdapter = true)
data class SyncGrocery(
    val productId: String,
    val groceryListId: String,
    val description: String?,
    val purchased: Boolean,
    val purchasedLastModified: Long
)

data class GroceryKey(
    val productId: String,
    val groceryListId: String
)

val SyncGrocery.key get() = GroceryKey(productId = productId, groceryListId = groceryListId)

/**
 * A product that was created by the user.
 */
@JsonClass(generateAdapter = true)
data class SyncCustomProduct(
    val id: String,
    val name: String,
    val iconId: String?,
    val categoryId: String?
)

/**
 * The customizable properties of a predefined product.
 */
@JsonClass(generateAdapter = true)
data class SyncDefaultProduct(
    val id: String,
    val iconId: String?,
    val categoryId: String?
)

@JsonClass(generateAdapter = true)
data class SyncCategory(
    val id: String,
    val sortingPriority: Long
)

/**
 * Ids of the predefined data that is present in the local database.
 * Records that reference anything missing here can't be stored locally yet.
 */
@JsonClass(generateAdapter = true)
data class LocalReferences(
    val iconIds: Set<String> = emptySet(),
    val categoryIds: Set<String> = emptySet(),
    val defaultProductIds: Set<String> = emptySet()
)

data class LocalSyncData(
    val snapshot: SyncSnapshot,
    val references: LocalReferences
)

/**
 * The last state that the local database and the server agreed on. It is the common ancestor
 * used to tell which side changed what since the previous sync.
 *
 * [references] are the local references at the time the base was recorded.
 */
@JsonClass(generateAdapter = true)
data class SyncBase(
    val snapshot: SyncSnapshot = SyncSnapshot(),
    val references: LocalReferences = LocalReferences()
)