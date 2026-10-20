package com.rendox.grocerygenius.database.sync

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.rendox.grocerygenius.database.category.CategoryEntity
import com.rendox.grocerygenius.database.grocery.GroceryEntity
import com.rendox.grocerygenius.database.grocerylist.GroceryListEntity
import com.rendox.grocerygenius.database.product.ProductEntity

/**
 * Reads and writes the user data that is synchronized with the WebDAV server.
 */
@Dao
interface SyncDao {
    @Query("SELECT * FROM GroceryListEntity")
    suspend fun getAllGroceryLists(): List<GroceryListEntity>

    @Query("SELECT * FROM GroceryEntity")
    suspend fun getAllGroceries(): List<GroceryEntity>

    @Query("SELECT * FROM ProductEntity")
    suspend fun getAllProducts(): List<ProductEntity>

    @Query("SELECT * FROM CategoryEntity")
    suspend fun getAllCategories(): List<CategoryEntity>

    @Query("SELECT uniqueFileName FROM IconEntity")
    suspend fun getAllIconIds(): List<String>

    @Upsert
    suspend fun upsertGroceryLists(groceryLists: List<GroceryListEntity>)

    @Upsert
    suspend fun upsertProducts(products: List<ProductEntity>)

    @Upsert
    suspend fun upsertGroceries(groceries: List<GroceryEntity>)

    @Query("UPDATE ProductEntity SET iconFileName = :iconId, categoryId = :categoryId WHERE id = :productId")
    suspend fun updateProductIconAndCategory(
        productId: String,
        iconId: String?,
        categoryId: String?
    )

    @Query("UPDATE CategoryEntity SET sortingPriority = :sortingPriority WHERE id = :categoryId")
    suspend fun updateCategorySortingPriority(
        categoryId: String,
        sortingPriority: Long
    )

    @Query("DELETE FROM GroceryEntity WHERE productId = :productId AND groceryListId = :groceryListId")
    suspend fun deleteGrocery(
        productId: String,
        groceryListId: String
    )

    @Query("DELETE FROM ProductEntity WHERE id IN (:ids)")
    suspend fun deleteProducts(ids: List<String>)

    @Query("DELETE FROM GroceryListEntity WHERE id IN (:ids)")
    suspend fun deleteGroceryLists(ids: List<String>)
}