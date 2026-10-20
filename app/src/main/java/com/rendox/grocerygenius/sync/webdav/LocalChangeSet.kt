package com.rendox.grocerygenius.sync.webdav

/**
 * The database operations that turn the local data into a target [SyncSnapshot].
 */
data class LocalChangeSet(
    val groceryListsToUpsert: List<SyncGroceryList> = emptyList(),
    val customProductsToUpsert: List<SyncCustomProduct> = emptyList(),
    val defaultProductsToUpdate: List<SyncDefaultProduct> = emptyList(),
    val categoriesToUpdate: List<SyncCategory> = emptyList(),
    val groceriesToUpsert: List<SyncGrocery> = emptyList(),
    val groceriesToDelete: List<GroceryKey> = emptyList(),
    val customProductIdsToDelete: List<String> = emptyList(),
    val groceryListIdsToDelete: List<String> = emptyList()
) {
    val isEmpty: Boolean
        get() = groceryListsToUpsert.isEmpty() &&
            customProductsToUpsert.isEmpty() &&
            defaultProductsToUpdate.isEmpty() &&
            categoriesToUpdate.isEmpty() &&
            groceriesToUpsert.isEmpty() &&
            groceriesToDelete.isEmpty() &&
            customProductIdsToDelete.isEmpty() &&
            groceryListIdsToDelete.isEmpty()

    companion object {
        /**
         * Calculates the changes needed to turn [local] into [target]. The local database only
         * holds references to predefined data that is present on this device, so missing icons and
         * categories are left empty, and groceries of missing default products are skipped. Default
         * products and categories are never created or deleted, only updated.
         */
        fun between(
            local: LocalSyncData,
            target: SyncSnapshot
        ): LocalChangeSet {
            val references = local.references
            val availableIcon = { id: String? -> id?.takeIf { it in references.iconIds } }
            val availableCategory = { id: String? -> id?.takeIf { it in references.categoryIds } }

            val localGroceryLists = local.snapshot.groceryLists.associateBy { it.id }
            val targetGroceryListIds = target.groceryLists.mapTo(HashSet()) { it.id }

            val localCustomProducts = local.snapshot.customProducts.associateBy { it.id }
            val targetCustomProducts = target.customProducts
                .filter { it.id !in references.defaultProductIds }
                .map { product ->
                    product.copy(
                        iconId = availableIcon(product.iconId),
                        categoryId = availableCategory(product.categoryId)
                    )
                }
            val targetCustomProductIds = targetCustomProducts.mapTo(HashSet()) { it.id }

            val localDefaultProducts = local.snapshot.defaultProducts.associateBy { it.id }
            val localCategories = local.snapshot.categories.associateBy { it.id }

            val localGroceries = local.snapshot.groceries.associateBy { it.key }
            val targetGroceryKeys = target.groceries.mapTo(HashSet()) { it.key }

            return LocalChangeSet(
                groceryListsToUpsert = target.groceryLists.filter { localGroceryLists[it.id] != it },
                customProductsToUpsert = targetCustomProducts.filter { localCustomProducts[it.id] != it },
                defaultProductsToUpdate = target.defaultProducts
                    .filter { it.id in localDefaultProducts }
                    .map { product ->
                        product.copy(
                            iconId = availableIcon(product.iconId),
                            categoryId = availableCategory(product.categoryId)
                        )
                    }
                    .filter { localDefaultProducts[it.id] != it },
                categoriesToUpdate = target.categories.filter { category ->
                    val localCategory = localCategories[category.id]
                    localCategory != null && localCategory != category
                },
                groceriesToUpsert = target.groceries.filter { grocery ->
                    val productIsAvailable = grocery.productId in references.defaultProductIds ||
                        grocery.productId in targetCustomProductIds
                    grocery.groceryListId in targetGroceryListIds &&
                        productIsAvailable &&
                        localGroceries[grocery.key] != grocery
                },
                groceriesToDelete = localGroceries.keys.filter { it !in targetGroceryKeys },
                customProductIdsToDelete = localCustomProducts.keys.filter { it !in targetCustomProductIds },
                groceryListIdsToDelete = localGroceryLists.keys.filter { it !in targetGroceryListIds }
            )
        }
    }
}