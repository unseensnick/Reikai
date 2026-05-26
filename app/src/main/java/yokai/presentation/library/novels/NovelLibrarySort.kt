package yokai.presentation.library.novels

import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.ui.library.LibrarySort
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.lang.removeArticles
import kotlin.random.Random

/**
 * Novel-side parallel of [yokai.presentation.library.manga.MangaLibrarySort]. Sorts each
 * category's items by the category's per-category sort mode (set via
 * [NovelCategory.changeSortTo] and stored in `novel_categories.sort`), falling back to the
 * library-wide default when unset.
 *
 * Behavior matches the manga helper verbatim: 9 modes, O(n log n) drag-and-drop, 0-unread
 * sinks to the bottom for the Unread mode, explicit DragAndDrop on a non-dynamic category
 * renders alphabetically. See [yokai.presentation.library.manga.MangaLibrarySort] for the
 * design notes on those quirks.
 *
 * Pure function: reads only its arguments. No Injekt, no preferences, no DB.
 */
object NovelLibrarySort {

    fun sort(
        library: Map<NovelCategory, List<LibraryItem.Novel>>,
        libraryDefaultMode: LibrarySort,
        libraryDefaultAscending: Boolean,
        randomSeed: Long,
        removeArticles: Boolean = false,
    ): Map<NovelCategory, List<LibraryItem.Novel>> {
        if (library.isEmpty()) return library
        val categoryOrderMap = library.keys.associate { (it.id ?: 0) to it.order }
        return library.mapValues { (category, items) ->
            sortCategory(
                items = items,
                category = category,
                libraryDefaultMode = libraryDefaultMode,
                libraryDefaultAscending = libraryDefaultAscending,
                randomSeed = randomSeed,
                removeArticles = removeArticles,
                categoryOrderMap = categoryOrderMap,
            )
        }
    }

    private fun sortCategory(
        items: List<LibraryItem.Novel>,
        category: NovelCategory,
        libraryDefaultMode: LibrarySort,
        libraryDefaultAscending: Boolean,
        randomSeed: Long,
        removeArticles: Boolean,
        categoryOrderMap: Map<Int, Int>,
    ): List<LibraryItem.Novel> {
        if (items.size <= 1) return items

        if (category.novelSort == null && category.novelOrder.isNotEmpty()) {
            val positionMap = category.novelOrder.withIndex()
                .associate { (idx, id) -> id to idx }
            return items.sortedWith(novelOrderFallbackComparator(positionMap, removeArticles))
        }

        val (mode, ascending) = resolveSortMode(
            category = category,
            libraryDefaultMode = libraryDefaultMode,
            libraryDefaultAscending = libraryDefaultAscending,
        )

        if (mode == LibrarySort.Random) {
            return items.shuffled(Random(randomSeed))
        }

        val comparator = buildComparator(
            mode = mode,
            ascending = ascending,
            removeArticles = removeArticles,
            isDynamic = category.isDynamic,
            categoryOrderMap = categoryOrderMap,
        )
        return items.sortedWith(comparator)
    }

    private fun resolveSortMode(
        category: NovelCategory,
        libraryDefaultMode: LibrarySort,
        libraryDefaultAscending: Boolean,
    ): Pair<LibrarySort, Boolean> {
        if (category.novelSort != null) {
            val mode = category.sortingMode() ?: LibrarySort.Title
            return mode to category.isAscending()
        }
        return libraryDefaultMode to libraryDefaultAscending
    }

    private fun buildComparator(
        mode: LibrarySort,
        ascending: Boolean,
        removeArticles: Boolean,
        isDynamic: Boolean,
        categoryOrderMap: Map<Int, Int>,
    ): Comparator<LibraryItem.Novel> {
        val primary = Comparator<LibraryItem.Novel> { a, b ->
            primaryCompare(a, b, mode, ascending, removeArticles, isDynamic, categoryOrderMap)
        }
        return primary.thenComparator { a, b -> titleCompare(a, b, removeArticles) }
    }

    private fun primaryCompare(
        a: LibraryItem.Novel,
        b: LibraryItem.Novel,
        mode: LibrarySort,
        ascending: Boolean,
        removeArticles: Boolean,
        isDynamic: Boolean,
        categoryOrderMap: Map<Int, Int>,
    ): Int {
        val aLn = a.libraryNovel
        val bLn = b.libraryNovel
        return when (mode) {
            LibrarySort.Title -> {
                val cmp = titleCompare(a, b, removeArticles)
                if (ascending) cmp else -cmp
            }
            LibrarySort.LatestChapter -> directional(ascending) {
                aLn.latestUpdate.compareTo(bLn.latestUpdate)
            }
            LibrarySort.Unread -> {
                when {
                    aLn.unread == 0 && bLn.unread == 0 -> 0
                    aLn.unread == 0 -> 1
                    bLn.unread == 0 -> -1
                    ascending -> aLn.unread.compareTo(bLn.unread)
                    else -> bLn.unread.compareTo(aLn.unread)
                }
            }
            LibrarySort.LastRead -> directional(ascending) {
                aLn.lastRead.compareTo(bLn.lastRead)
            }
            LibrarySort.TotalChapters -> directional(ascending) {
                aLn.totalChapters.compareTo(bLn.totalChapters)
            }
            LibrarySort.DateFetched -> directional(ascending) {
                aLn.lastFetch.compareTo(bLn.lastFetch)
            }
            LibrarySort.DateAdded -> directional(ascending) {
                aLn.novel.dateAdded.compareTo(bLn.novel.dateAdded)
            }
            LibrarySort.DragAndDrop -> {
                if (isDynamic) {
                    val aOrder = categoryOrderMap[aLn.category] ?: 0
                    val bOrder = categoryOrderMap[bLn.category] ?: 0
                    directional(ascending) { aOrder.compareTo(bOrder) }
                } else {
                    0
                }
            }
            LibrarySort.Random -> 0
        }
    }

    private inline fun directional(ascending: Boolean, block: () -> Int): Int {
        val cmp = block()
        return if (ascending) cmp else -cmp
    }

    private fun titleCompare(
        a: LibraryItem.Novel,
        b: LibraryItem.Novel,
        removeArticles: Boolean,
    ): Int {
        val aTitle = a.libraryNovel.novel.title
        val bTitle = b.libraryNovel.novel.title
        return if (removeArticles) {
            aTitle.removeArticles().compareTo(bTitle.removeArticles(), ignoreCase = true)
        } else {
            aTitle.compareTo(bTitle, ignoreCase = true)
        }
    }

    private fun novelOrderFallbackComparator(
        positionMap: Map<Long, Int>,
        removeArticles: Boolean,
    ): Comparator<LibraryItem.Novel> {
        return Comparator<LibraryItem.Novel> { a, b ->
            val aIdx = a.libraryNovel.novel.id?.let { positionMap[it] } ?: Int.MIN_VALUE
            val bIdx = b.libraryNovel.novel.id?.let { positionMap[it] } ?: Int.MIN_VALUE
            aIdx.compareTo(bIdx)
        }.thenComparator { a, b -> titleCompare(a, b, removeArticles) }
    }
}
