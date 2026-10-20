package com.rendox.grocerygenius.sync.webdav

/**
 * Three-way merge of [SyncSnapshot]s.
 *
 * Every record is compared with its version in the base, which is the state that both sides
 * agreed on during the previous sync. A record that was changed on one side only takes that
 * side's version. When both sides changed the same record, its fields are merged one by one.
 * If both sides changed the same field, the local value wins, except for the purchase state of a
 * grocery, where the most recent change wins. Records that exist on both sides but not in the
 * base (e.g. when a device syncs for the first time) prefer the remote values, so a new device
 * adopts the customizations that are already on the server.
 *
 * A record that was deleted on one side but modified on the other side is kept.
 */
object SnapshotMerger {

    fun merge(
        base: SyncSnapshot,
        local: SyncSnapshot,
        remote: SyncSnapshot
    ): SyncSnapshot {
        val groceryLists = mergeRecords(
            base = base.groceryLists,
            local = local.groceryLists,
            remote = remote.groceryLists,
            key = { it.id }
        ) { merger ->
            merger.local.copy(
                name = merger.pick { it.name },
                sortingPriority = merger.pick { it.sortingPriority }
            )
        }
        val customProducts = mergeRecords(
            base = base.customProducts,
            local = local.customProducts,
            remote = remote.customProducts,
            key = { it.id }
        ) { merger ->
            merger.local.copy(
                name = merger.pick { it.name },
                iconId = merger.pick { it.iconId },
                categoryId = merger.pick { it.categoryId }
            )
        }
        val defaultProducts = mergeRecords(
            base = base.defaultProducts,
            local = local.defaultProducts,
            remote = remote.defaultProducts,
            key = { it.id }
        ) { merger ->
            merger.local.copy(
                iconId = merger.pick { it.iconId },
                categoryId = merger.pick { it.categoryId }
            )
        }
        val categories = mergeRecords(
            base = base.categories,
            local = local.categories,
            remote = remote.categories,
            key = { it.id }
        ) { merger ->
            merger.local.copy(sortingPriority = merger.pick { it.sortingPriority })
        }
        val groceries = mergeRecords(
            base = base.groceries,
            local = local.groceries,
            remote = remote.groceries,
            key = { it.key }
        ) { merger ->
            val purchaseState = merger.pick(
                selector = { PurchaseState(it.purchased, it.purchasedLastModified) },
                resolveConflict = { localState, remoteState ->
                    if (remoteState.lastModified > localState.lastModified) remoteState else localState
                }
            )
            merger.local.copy(
                description = merger.pick { it.description },
                purchased = purchaseState.purchased,
                purchasedLastModified = purchaseState.lastModified
            )
        }

        // drop groceries whose list or custom product was deleted
        val groceryListIds = groceryLists.mapTo(HashSet()) { it.id }
        val customProductIds = customProducts.mapTo(HashSet()) { it.id }
        val deletedCustomProductIds = sequenceOf(base, local, remote)
            .flatMap { snapshot -> snapshot.customProducts.asSequence() }
            .map { it.id }
            .filterTo(HashSet()) { it !in customProductIds }
        val validGroceries = groceries.filter { grocery ->
            grocery.groceryListId in groceryListIds && grocery.productId !in deletedCustomProductIds
        }

        return SyncSnapshot(
            groceryLists = groceryLists,
            groceries = validGroceries,
            customProducts = customProducts,
            defaultProducts = defaultProducts,
            categories = categories
        ).canonical()
    }

    /**
     * Prepares the local snapshot for a merge with [base].
     *
     * Records that reference predefined data missing on this device can't be stored in the
     * local database, so their absence doesn't mean that the user deleted them. The same applies
     * to predefined data that was missing at the time of the previous sync, because records
     * referencing it couldn't be stored back then. Such records and references are restored to
     * their base values, so that the merge treats them as unchanged.
     */
    fun restoreUnavailableRecords(
        local: LocalSyncData,
        base: SyncBase
    ): SyncSnapshot {
        val current = local.references
        val previous = base.references
        val isIconUnavailable = { id: String -> id !in current.iconIds || id !in previous.iconIds }
        val isCategoryUnavailable = { id: String ->
            id !in current.categoryIds || id !in previous.categoryIds
        }
        val isDefaultProductUnavailable = { id: String ->
            id !in current.defaultProductIds || id !in previous.defaultProductIds
        }

        // a missing reference is stored as null in the database
        fun restoreReference(
            localValue: String?,
            baseValue: String?,
            isUnavailable: (String) -> Boolean
        ): String? = if (localValue == null && baseValue != null && isUnavailable(baseValue)) {
            baseValue
        } else {
            localValue
        }

        val baseCustomProducts = base.snapshot.customProducts.associateBy { it.id }
        val customProducts = local.snapshot.customProducts.map { product ->
            val baseProduct = baseCustomProducts[product.id] ?: return@map product
            product.copy(
                iconId = restoreReference(product.iconId, baseProduct.iconId, isIconUnavailable),
                categoryId = restoreReference(
                    product.categoryId,
                    baseProduct.categoryId,
                    isCategoryUnavailable
                )
            )
        }

        val baseDefaultProducts = base.snapshot.defaultProducts.associateBy { it.id }
        val localDefaultProducts = local.snapshot.defaultProducts.associateBy { it.id }
        val defaultProducts = (localDefaultProducts.keys + baseDefaultProducts.keys).mapNotNull { id ->
            val baseProduct = baseDefaultProducts[id]
            val localProduct = localDefaultProducts[id]
            when {
                baseProduct != null && isDefaultProductUnavailable(id) -> baseProduct
                baseProduct != null && localProduct != null -> localProduct.copy(
                    iconId = restoreReference(localProduct.iconId, baseProduct.iconId, isIconUnavailable),
                    categoryId = restoreReference(
                        localProduct.categoryId,
                        baseProduct.categoryId,
                        isCategoryUnavailable
                    )
                )

                else -> localProduct
            }
        }

        val baseCategories = base.snapshot.categories.associateBy { it.id }
        val localCategories = local.snapshot.categories.associateBy { it.id }
        val categories = (localCategories.keys + baseCategories.keys).mapNotNull { id ->
            val baseCategory = baseCategories[id]
            if (baseCategory != null && isCategoryUnavailable(id)) baseCategory else localCategories[id]
        }

        val localGroceryListIds = local.snapshot.groceryLists.mapTo(HashSet()) { it.id }
        val knownCustomProductIds = (base.snapshot.customProducts + local.snapshot.customProducts)
            .mapTo(HashSet()) { it.id }
        val localGroceryKeys = local.snapshot.groceries.mapTo(HashSet()) { it.key }
        val restoredGroceries = base.snapshot.groceries.filter { grocery ->
            val referencesDefaultProduct = grocery.productId !in knownCustomProductIds
            referencesDefaultProduct &&
                isDefaultProductUnavailable(grocery.productId) &&
                grocery.groceryListId in localGroceryListIds &&
                grocery.key !in localGroceryKeys
        }

        return local.snapshot.copy(
            groceries = local.snapshot.groceries + restoredGroceries,
            customProducts = customProducts,
            defaultProducts = defaultProducts,
            categories = categories
        ).canonical()
    }

    private fun <K, T : Any> mergeRecords(
        base: List<T>,
        local: List<T>,
        remote: List<T>,
        key: (T) -> K,
        mergeConflict: (FieldMerger<T>) -> T
    ): List<T> {
        val baseRecords = base.associateBy(key)
        val localRecords = local.associateBy(key)
        val remoteRecords = remote.associateBy(key)
        return (baseRecords.keys + localRecords.keys + remoteRecords.keys).mapNotNull { recordKey ->
            val baseRecord = baseRecords[recordKey]
            val localRecord = localRecords[recordKey]
            val remoteRecord = remoteRecords[recordKey]
            when {
                localRecord != null && remoteRecord != null -> when (baseRecord) {
                    localRecord -> remoteRecord
                    remoteRecord -> localRecord
                    else -> if (localRecord == remoteRecord) {
                        localRecord
                    } else {
                        mergeConflict(FieldMerger(baseRecord, localRecord, remoteRecord))
                    }
                }
                // deleted remotely, unless it was changed locally
                localRecord != null -> localRecord.takeIf { it != baseRecord }
                // deleted locally, unless it was changed remotely
                remoteRecord != null -> remoteRecord.takeIf { it != baseRecord }
                else -> null
            }
        }
    }

    private class FieldMerger<T : Any>(
        val base: T?,
        val local: T,
        val remote: T
    ) {
        fun <F> pick(selector: (T) -> F): F = pick(selector) { localValue, remoteValue ->
            if (base != null) localValue else remoteValue
        }

        fun <F> pick(
            selector: (T) -> F,
            resolveConflict: (localValue: F, remoteValue: F) -> F
        ): F {
            val localValue = selector(local)
            val remoteValue = selector(remote)
            if (localValue == remoteValue) return localValue
            if (base != null) {
                val baseValue = selector(base)
                if (localValue == baseValue) return remoteValue
                if (remoteValue == baseValue) return localValue
            }
            return resolveConflict(localValue, remoteValue)
        }
    }

    private data class PurchaseState(
        val purchased: Boolean,
        val lastModified: Long
    )
}