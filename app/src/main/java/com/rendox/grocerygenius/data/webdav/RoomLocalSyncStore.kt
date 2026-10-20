package com.rendox.grocerygenius.data.webdav

import androidx.room.withTransaction
import com.rendox.grocerygenius.database.AppDatabase
import com.rendox.grocerygenius.database.grocery.GroceryEntity
import com.rendox.grocerygenius.database.grocerylist.GroceryListEntity
import com.rendox.grocerygenius.database.product.ProductEntity
import com.rendox.grocerygenius.database.sync.SyncDao
import com.rendox.grocerygenius.sync.webdav.LocalChangeSet
import com.rendox.grocerygenius.sync.webdav.LocalReferences
import com.rendox.grocerygenius.sync.webdav.LocalSyncData
import com.rendox.grocerygenius.sync.webdav.LocalSyncStore
import com.rendox.grocerygenius.sync.webdav.SyncCategory
import com.rendox.grocerygenius.sync.webdav.SyncCustomProduct
import com.rendox.grocerygenius.sync.webdav.SyncDefaultProduct
import com.rendox.grocerygenius.sync.webdav.SyncGrocery
import com.rendox.grocerygenius.sync.webdav.SyncGroceryList
import com.rendox.grocerygenius.sync.webdav.SyncSnapshot
import javax.inject.Inject

class RoomLocalSyncStore @Inject constructor(
    private val appDatabase: AppDatabase,
    private val syncDao: SyncDao
) : LocalSyncStore {
    override suspend fun read(): LocalSyncData = appDatabase.withTransaction { readLocalData() }

    override suspend fun update(transform: (LocalSyncData) -> SyncSnapshot): LocalSyncData =
        appDatabase.withTransaction {
            val local = readLocalData()
            val target = transform(local)
            apply(LocalChangeSet.between(local, target))
            LocalSyncData(snapshot = target, references = local.references)
        }

    private suspend fun readLocalData(): LocalSyncData {
        val (defaultProducts, customProducts) = syncDao.getAllProducts().partition { it.isDefault }
        val categories = syncDao.getAllCategories()
        val snapshot = SyncSnapshot(
            groceryLists = syncDao.getAllGroceryLists().map { groceryList ->
                SyncGroceryList(
                    id = groceryList.id,
                    name = groceryList.name,
                    sortingPriority = groceryList.sortingPriority
                )
            },
            groceries = syncDao.getAllGroceries().map { grocery ->
                SyncGrocery(
                    productId = grocery.productId,
                    groceryListId = grocery.groceryListId,
                    description = grocery.description,
                    purchased = grocery.purchased,
                    purchasedLastModified = grocery.purchasedLastModified
                )
            },
            customProducts = customProducts.map { product ->
                SyncCustomProduct(
                    id = product.id,
                    name = product.name,
                    iconId = product.iconFileName,
                    categoryId = product.categoryId
                )
            },
            defaultProducts = defaultProducts.map { product ->
                SyncDefaultProduct(
                    id = product.id,
                    iconId = product.iconFileName,
                    categoryId = product.categoryId
                )
            },
            categories = categories.map { category ->
                SyncCategory(id = category.id, sortingPriority = category.sortingPriority)
            }
        )
        return LocalSyncData(
            snapshot = snapshot.canonical(),
            references = LocalReferences(
                iconIds = syncDao.getAllIconIds().toSet(),
                categoryIds = categories.mapTo(HashSet()) { it.id },
                defaultProductIds = defaultProducts.mapTo(HashSet()) { it.id }
            )
        )
    }

    /**
     * Applies the changes in an order that keeps the foreign keys valid.
     */
    private suspend fun apply(changes: LocalChangeSet) {
        if (changes.isEmpty) return
        if (changes.groceryListsToUpsert.isNotEmpty()) {
            syncDao.upsertGroceryLists(
                changes.groceryListsToUpsert.map { groceryList ->
                    GroceryListEntity(
                        id = groceryList.id,
                        name = groceryList.name,
                        sortingPriority = groceryList.sortingPriority
                    )
                }
            )
        }
        if (changes.customProductsToUpsert.isNotEmpty()) {
            syncDao.upsertProducts(
                changes.customProductsToUpsert.map { product ->
                    ProductEntity(
                        id = product.id,
                        name = product.name,
                        isDefault = false,
                        iconFileName = product.iconId,
                        categoryId = product.categoryId
                    )
                }
            )
        }
        for (product in changes.defaultProductsToUpdate) {
            syncDao.updateProductIconAndCategory(
                productId = product.id,
                iconId = product.iconId,
                categoryId = product.categoryId
            )
        }
        for (category in changes.categoriesToUpdate) {
            syncDao.updateCategorySortingPriority(
                categoryId = category.id,
                sortingPriority = category.sortingPriority
            )
        }
        if (changes.groceriesToUpsert.isNotEmpty()) {
            syncDao.upsertGroceries(
                changes.groceriesToUpsert.map { grocery ->
                    GroceryEntity(
                        productId = grocery.productId,
                        groceryListId = grocery.groceryListId,
                        description = grocery.description,
                        purchased = grocery.purchased,
                        purchasedLastModified = grocery.purchasedLastModified
                    )
                }
            )
        }
        for (grocery in changes.groceriesToDelete) {
            syncDao.deleteGrocery(productId = grocery.productId, groceryListId = grocery.groceryListId)
        }
        // stay below the limit of SQLite query parameters
        changes.customProductIdsToDelete.chunked(MAX_QUERY_PARAMETERS).forEach { ids ->
            syncDao.deleteProducts(ids)
        }
        changes.groceryListIdsToDelete.chunked(MAX_QUERY_PARAMETERS).forEach { ids ->
            syncDao.deleteGroceryLists(ids)
        }
    }

    companion object {
        private const val MAX_QUERY_PARAMETERS = 500
    }
}