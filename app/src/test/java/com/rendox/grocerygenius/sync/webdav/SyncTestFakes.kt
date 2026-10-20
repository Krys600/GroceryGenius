package com.rendox.grocerygenius.sync.webdav

import com.google.common.truth.Truth.assertWithMessage
import com.squareup.moshi.Moshi

val testSerializer = SyncSnapshotSerializer(Moshi.Builder().build())

val predefinedReferences = LocalReferences(
    iconIds = setOf("apple.svg", "bread.svg", "milk.svg"),
    categoryIds = setOf("fruits", "bakery", "dairy"),
    defaultProductIds = setOf("apple", "bread", "milk")
)

val predefinedDefaultProducts = listOf(
    SyncDefaultProduct(id = "apple", iconId = "apple.svg", categoryId = "fruits"),
    SyncDefaultProduct(id = "bread", iconId = "bread.svg", categoryId = "bakery"),
    SyncDefaultProduct(id = "milk", iconId = "milk.svg", categoryId = "dairy")
)

val predefinedCategories = listOf(
    SyncCategory(id = "fruits", sortingPriority = 0),
    SyncCategory(id = "bakery", sortingPriority = 1),
    SyncCategory(id = "dairy", sortingPriority = 2)
)

/**
 * An in-memory database of a device that enforces the same constraints as the Room database:
 * groceries must reference existing lists and products, references to missing icons and
 * categories are not allowed, and deleting a list or a product deletes its groceries.
 */
class FakeLocalSyncStore(
    var references: LocalReferences = predefinedReferences,
    var snapshot: SyncSnapshot = SyncSnapshot(
        defaultProducts = predefinedDefaultProducts
            .filter { it.id in references.defaultProductIds }
            .map { product -> product.copy(iconId = product.iconId?.takeIf { it in references.iconIds }) },
        categories = predefinedCategories.filter { it.id in references.categoryIds }
    )
) : LocalSyncStore {
    var onUpdateApplied: (() -> Unit)? = null

    override suspend fun read() = LocalSyncData(snapshot.canonical(), references)

    override suspend fun update(transform: (LocalSyncData) -> SyncSnapshot): LocalSyncData {
        val local = read()
        val target = transform(local)
        apply(LocalChangeSet.between(local, target))
        onUpdateApplied?.invoke()
        return LocalSyncData(target, local.references)
    }

    private fun apply(changes: LocalChangeSet) {
        val upsertedListIds = changes.groceryListsToUpsert.map { it.id }.toSet()
        snapshot = snapshot.copy(
            groceryLists = snapshot.groceryLists.filter { it.id !in upsertedListIds } +
                changes.groceryListsToUpsert
        )
        changes.customProductsToUpsert.forEach { product ->
            checkReferences(product.iconId, product.categoryId)
        }
        val upsertedProductIds = changes.customProductsToUpsert.map { it.id }.toSet()
        snapshot = snapshot.copy(
            customProducts = snapshot.customProducts.filter { it.id !in upsertedProductIds } +
                changes.customProductsToUpsert
        )
        changes.defaultProductsToUpdate.forEach { product ->
            checkReferences(product.iconId, product.categoryId)
        }
        val updatedDefaultProducts = changes.defaultProductsToUpdate.associateBy { it.id }
        snapshot = snapshot.copy(
            defaultProducts = snapshot.defaultProducts.map { updatedDefaultProducts[it.id] ?: it }
        )
        val updatedCategories = changes.categoriesToUpdate.associateBy { it.id }
        snapshot = snapshot.copy(categories = snapshot.categories.map { updatedCategories[it.id] ?: it })

        val productIds = (snapshot.customProducts.map { it.id } + snapshot.defaultProducts.map { it.id }).toSet()
        val listIds = snapshot.groceryLists.map { it.id }.toSet()
        changes.groceriesToUpsert.forEach { grocery ->
            assertWithMessage("FK violation: list of $grocery").that(listIds).contains(grocery.groceryListId)
            assertWithMessage("FK violation: product of $grocery").that(productIds).contains(grocery.productId)
        }
        val upsertedGroceryKeys = changes.groceriesToUpsert.map { it.key }.toSet()
        snapshot = snapshot.copy(
            groceries = snapshot.groceries.filter { it.key !in upsertedGroceryKeys } + changes.groceriesToUpsert
        )
        val deletedGroceryKeys = changes.groceriesToDelete.toSet()
        snapshot = snapshot.copy(groceries = snapshot.groceries.filter { it.key !in deletedGroceryKeys })
        changes.customProductIdsToDelete.forEach(::deleteCustomProduct)
        changes.groceryListIdsToDelete.forEach(::deleteGroceryList)
    }

    private fun checkReferences(
        iconId: String?,
        categoryId: String?
    ) {
        iconId?.let { assertWithMessage("FK violation: icon").that(references.iconIds).contains(it) }
        categoryId?.let { assertWithMessage("FK violation: category").that(references.categoryIds).contains(it) }
    }

    fun addGroceryList(
        id: String,
        name: String = id,
        sortingPriority: Long = 0
    ) {
        snapshot = snapshot.copy(groceryLists = snapshot.groceryLists + SyncGroceryList(id, name, sortingPriority))
    }

    fun renameGroceryList(
        id: String,
        name: String
    ) {
        snapshot = snapshot.copy(
            groceryLists = snapshot.groceryLists.map { if (it.id == id) it.copy(name = name) else it }
        )
    }

    fun deleteGroceryList(id: String) {
        snapshot = snapshot.copy(
            groceryLists = snapshot.groceryLists.filter { it.id != id },
            groceries = snapshot.groceries.filter { it.groceryListId != id }
        )
    }

    fun addCustomProduct(
        id: String,
        name: String = id,
        iconId: String? = null,
        categoryId: String? = null
    ) {
        checkReferences(iconId, categoryId)
        snapshot = snapshot.copy(
            customProducts = snapshot.customProducts + SyncCustomProduct(id, name, iconId, categoryId)
        )
    }

    fun deleteCustomProduct(id: String) {
        snapshot = snapshot.copy(
            customProducts = snapshot.customProducts.filter { it.id != id },
            groceries = snapshot.groceries.filter { it.productId != id }
        )
    }

    fun addGrocery(
        productId: String,
        listId: String,
        description: String? = null,
        purchased: Boolean = false,
        purchasedLastModified: Long = 0
    ) {
        snapshot = snapshot.copy(
            groceries = snapshot.groceries +
                SyncGrocery(productId, listId, description, purchased, purchasedLastModified)
        )
    }

    fun updateGrocery(
        productId: String,
        listId: String,
        update: (SyncGrocery) -> SyncGrocery
    ) {
        snapshot = snapshot.copy(
            groceries = snapshot.groceries.map {
                if (it.productId == productId && it.groceryListId == listId) update(it) else it
            }
        )
    }

    fun removeGrocery(
        productId: String,
        listId: String
    ) {
        snapshot = snapshot.copy(
            groceries = snapshot.groceries.filterNot {
                it.productId == productId && it.groceryListId == listId
            }
        )
    }

    fun updateDefaultProduct(
        id: String,
        iconId: String?,
        categoryId: String?
    ) {
        checkReferences(iconId, categoryId)
        snapshot = snapshot.copy(
            defaultProducts = snapshot.defaultProducts.map {
                if (it.id == id) it.copy(iconId = iconId, categoryId = categoryId) else it
            }
        )
    }

    fun updateCategoryPriority(
        id: String,
        sortingPriority: Long
    ) {
        snapshot = snapshot.copy(
            categories = snapshot.categories.map { if (it.id == id) it.copy(sortingPriority = sortingPriority) else it }
        )
    }

    /**
     * Simulates fetching the predefined data from GitHub on a device that didn't have it.
     */
    fun downloadPredefinedData() {
        references = predefinedReferences
        snapshot = snapshot.copy(
            defaultProducts = predefinedDefaultProducts,
            categories = predefinedCategories
        )
    }

    fun groceries() = snapshot.canonical().groceries
    fun groceryLists() = snapshot.canonical().groceryLists
}

class FakeSyncBaseStore : SyncBaseStore {
    var base: SyncBase? = null

    override suspend fun read() = base

    override suspend fun write(base: SyncBase) {
        // store it the same way as the real store does to catch serialization issues
        this.base = testSerializer.decodeBase(testSerializer.encodeBase(base))
    }

    override suspend fun clear() {
        base = null
    }
}

class FakeRemoteSnapshotStore : RemoteSnapshotStore {
    var file: RemoteSnapshotFile? = null
    private var version = 0

    /**
     * Invoked before an upload is processed, e.g. to simulate another device that uploads concurrently.
     */
    var beforeUpload: (suspend () -> Unit)? = null
    var failUploads = false
    var uploadCount = 0
        private set

    val snapshot get() = file?.let { testSerializer.decodeSnapshot(it.content) }

    override suspend fun download() = file

    override suspend fun upload(
        content: String,
        replacing: RemoteSnapshotFile?
    ): UploadResult {
        beforeUpload?.let { action ->
            beforeUpload = null
            action()
        }
        if (failUploads) throw java.io.IOException("Network is down")
        val current = file
        if (replacing == null && current != null) return UploadResult.PRECONDITION_FAILED
        if (replacing != null && current?.etag != replacing.etag) return UploadResult.PRECONDITION_FAILED
        version++
        uploadCount++
        file = RemoteSnapshotFile(content, etag = "\"$version\"")
        return UploadResult.UPLOADED
    }
}

class FakeDevice(val local: FakeLocalSyncStore = FakeLocalSyncStore()) {
    val baseStore = FakeSyncBaseStore()
    private val engine = WebDavSyncEngine(local, baseStore, testSerializer)

    suspend fun syncWith(remote: RemoteSnapshotStore) = engine.sync(remote)
}