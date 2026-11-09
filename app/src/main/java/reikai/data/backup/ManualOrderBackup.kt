package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManualOrderFields
import eu.kanade.tachiyomi.data.backup.models.BackupManualPosition
import reikai.domain.library.ContentType
import reikai.domain.library.LibraryManualOrderRepository
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/** The Default category's order, the system row's own, which a backup never lists among its categories. */
private const val DEFAULT_CATEGORY_ORDER = -1L

/**
 * The categories step both content types share: each series' categories by order, from [byEntry], set
 * through [setOrders], and its places in Manual orders, which name those same categories.
 */
suspend fun <E, B : BackupManualOrderFields> List<Pair<E, B>>.writeCategories(
    contentType: ContentType,
    idOf: (E) -> Long,
    byEntry: Map<Long, List<Category>>,
    setOrders: B.(List<Long>) -> Unit,
    manualOrder: LibraryManualOrderRepository,
    categories: CategoryRepository,
) {
    writeRows(byEntry, idOf) { _, backup, rows -> backup.setOrders(rows.map { it.order }) }
    writeManualOrder(contentType, idOf, manualOrder, categories)
}

/**
 * Writes each entry's places in Manual orders onto its backup, both content types alike: a place names its
 * category by order, as the entry's categories do, since category ids differ between devices.
 */
suspend fun <E, B : BackupManualOrderFields> List<Pair<E, B>>.writeManualOrder(
    contentType: ContentType,
    idOf: (E) -> Long,
    manualOrder: LibraryManualOrderRepository,
    categories: CategoryRepository,
) {
    val places = manualOrder.getForEntries(contentType, map { idOf(it.first) }).groupBy { it.entryId }
    if (places.isEmpty()) return
    val orderOf = categories.getUnfiltered().associate { it.id to it.order }
    forEach { (entry, backup) ->
        backup.manualOrder = places[idOf(entry)].orEmpty().mapNotNull { place ->
            orderOf[place.categoryId]?.let { BackupManualPosition(it, place.position) }
        }
    }
}

/**
 * A backup entry's places as this device's (category id, position) pairs: Default by its fixed order, any
 * other category through [categoryIdOf], which maps a backup order to a device category by name.
 */
fun BackupManualOrderFields.restoredManualOrder(categoryIdOf: (order: Long) -> Long?): List<Pair<Long, Long>> =
    manualOrder.mapNotNull { place ->
        val categoryId = if (place.categoryOrder == DEFAULT_CATEGORY_ORDER) {
            Category.UNCATEGORIZED_ID
        } else {
            categoryIdOf(place.categoryOrder)
        }
        categoryId?.let { it to place.position }
    }
