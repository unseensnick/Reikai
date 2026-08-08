package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.category.isHidden
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.LibrarySortFields
import reikai.domain.library.librarySortComparator
import reikai.domain.library.sortForCategory
import reikai.domain.library.toSortMode
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort

/**
 * The assembly inputs besides the rows and categories. One value each: these describe the list, not a
 * content type, per the All-first rule.
 */
data class LibraryAssemblyInputs(
    val globalSort: LibrarySort,
    val randomSeed: Long,
    val showHiddenCategories: Boolean,
    /** The category-sort-order pref (0 manual, 1 A-Z, 2 Z-A), applied via [reikaiSortCategories]. */
    val categorySortOrder: Int,
)

/**
 * One list assembly for every chip: bucket into categories, order them, sort within each (per-category
 * override or the global sort), then apply the empty-category rules. The chips only change what [rows]
 * and [categories] contain; the algorithm is chip-blind, and buckets before sorting because the sort is
 * per category. Values are [LibraryItem]s, never raw ids: a manga and a novel can share a raw table id,
 * so any Long-keyed structure over a mixed list silently cross-wires. The custom-info overlay is NOT
 * applied here. Rules pinned by LibraryAssemblyTest; record: docs/dev/plans/library-all-chip.md.
 */
fun assembleLibrary(
    rows: List<LibraryItem>,
    categories: List<Category>,
    inputs: LibraryAssemblyInputs,
    fields: LibrarySortFields<LibraryItem>,
): List<Pair<Category, List<LibraryItem>>> {
    val buckets = HashMap<Long, MutableList<LibraryItem>>()
    rows.forEach { item ->
        val categoryIds = item.libraryManga.categories.filter { it != Category.UNCATEGORIZED_ID }
        if (categoryIds.isEmpty()) {
            buckets.getOrPut(Category.UNCATEGORIZED_ID) { mutableListOf() }.add(item)
        } else {
            categoryIds.forEach { buckets.getOrPut(it) { mutableListOf() }.add(item) }
        }
    }

    return categories
        .distinctBy { it.id }
        .filter { inputs.showHiddenCategories || !it.isHidden }
        .sortedBy { it.order }
        .let { reikaiSortCategories(it, inputs.categorySortOrder) }
        .mapNotNull { category ->
            val bucket = buckets[category.id] ?: return@mapNotNull null
            val sort = sortForCategory(category, inputs.globalSort)
            val comparator =
                librarySortComparator(sort.type.toSortMode(), sort.isAscending, inputs.randomSeed, fields)
            category to bucket.sortedWith(comparator)
        }
}

/**
 * The assembled list the tab renders: the ordered categories, the per-category display read as a
 * function so the per-type custom-info overlay is applied at read time, only for what is rendered, and
 * the one count rule: the chip-filtered bucket size, shown when the count preference is on or a
 * search is active, on every chip.
 * [chip] is the view this output was assembled for: the flow lags a chip flip by one emission, so the
 * tab renders an assembly only when its chip matches, falling back to the provider's own list meanwhile.
 */
class LibraryAssembled(
    val chip: ContentType,
    val categories: List<Category>,
    private val items: (Category) -> List<LibraryItem>,
    private val counts: (Category) -> Int? = { null },
) {
    fun itemsFor(category: Category): List<LibraryItem> = items(category)
    fun countFor(category: Category): Int? = counts(category)
}

/**
 * Sort fields for a mixed list: [libraryItemSortFields] with a type-unique id key. The comparator's only
 * id use is the Random rank, `Random(seed + id)`, and a manga and a novel sharing a raw id would rank
 * identically, gluing the pair together under Random. Novel ids are negated (rowids are positive, so the
 * spaces cannot meet); this perturbs only the Random rank, never an identity.
 */
fun mixedLibraryItemSortFields(trackerMean: (LibraryItem) -> Double): LibrarySortFields<LibraryItem> {
    val base = libraryItemSortFields(trackerMean)
    return LibrarySortFields(
        id = { if (it.entryId is EntryId.Novel) -it.id else it.id },
        title = base.title,
        lastRead = base.lastRead,
        lastUpdate = base.lastUpdate,
        unreadCount = base.unreadCount,
        totalChapters = base.totalChapters,
        latestUpload = base.latestUpload,
        chapterFetchedAt = base.chapterFetchedAt,
        dateAdded = base.dateAdded,
        downloadCount = base.downloadCount,
        trackerMean = base.trackerMean,
    )
}
