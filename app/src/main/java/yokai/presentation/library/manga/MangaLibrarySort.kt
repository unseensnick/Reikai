package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.ui.library.LibrarySort
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.lang.removeArticles
import kotlin.random.Random

/**
 * Sort each category's items according to the category's per-category sort mode (set via
 * [Category.changeSortTo] and persisted in `categories.sq`'s `sort` column), falling back to
 * the library-wide default when unset. Behavioral port of
 * [eu.kanade.tachiyomi.ui.library.LibraryPresenter.applySort] at
 * `app/src/main/java/eu/kanade/tachiyomi/ui/library/LibraryPresenter.kt:683-794`.
 *
 * Design choices that diverge from legacy (all in the name of avoiding the verified legacy
 * lag / DB-write hot path):
 *
 *  - **No DB writes.** The legacy auto-initialises `category.mangaSort` via `changeSortTo()` +
 *    `updateCategories.awaitOne()` inside every `applySort` pass for un-initialised categories.
 *    Phase 6 keeps `sort()` pure; default-mode init happens in a one-shot screen-model side
 *    effect (C5+) so the hot path doesn't churn the categories table.
 *  - **O(n log n) drag-and-drop sort.** Legacy `category.mangaOrder.indexOf(id)` is O(n) per
 *    comparison → O(n² log n) total. Phase 6 precomputes a `Map<Long, Int>` once per category
 *    (O(n) prep) so each comparator call is O(1) → total O(n log n).
 *  - **Title fall-through is always stable.** Every comparator returns 0 on a tie and the
 *    builder appends a title comparator via `thenComparator`, mirroring the legacy line
 *    742-744 fallthrough.
 *
 * Same legacy quirks preserved:
 *
 *  - **0-unread sinks to the bottom regardless of direction** for the Unread mode (legacy
 *    `LibraryPresenter:697-702`'s combined-with-line-726 behavior collapsed into a single
 *    direction-aware branch).
 *  - **Explicit DragAndDrop on a non-dynamic category renders alphabetically.** Legacy
 *    `LibraryPresenter:719` returns `sortAlphabetical` here; drag-and-drop position is only
 *    consulted in the no-mode `mangaOrder.isNotEmpty()` fallback at line 729-739. Phase 6
 *    matches: explicit DragAndDrop = alphabetical, implicit mangaOrder fallback = position.
 *
 * Pure function: reads only its arguments. No Injekt, no preferences, no DB.
 */
object MangaLibrarySort {

    /**
     * @param library category-keyed map of items as produced by upstream stages (sectioner +
     *   grouping).
     * @param libraryDefaultMode the library-wide default sort mode, used when a category has
     *   no explicit mode and no `mangaOrder`. Source: `preferences.librarySortingMode()`.
     * @param libraryDefaultAscending the library-wide default direction. Source:
     *   `preferences.librarySortingAscending()`.
     * @param randomSeed seed for [LibrarySort.Random]. Source: `libraryPreferences.randomSortSeed()`.
     * @param removeArticles when true, the title tiebreaker (and Title mode) ignores leading
     *   articles like "A", "An", "The". Source: `preferences.removeArticles()`.
     */
    fun sort(
        library: Map<Category, List<LibraryItem.Manga>>,
        libraryDefaultMode: LibrarySort,
        libraryDefaultAscending: Boolean,
        randomSeed: Long,
        removeArticles: Boolean = false,
    ): Map<Category, List<LibraryItem.Manga>> {
        if (library.isEmpty()) return library
        // Pre-compute the category-id → order map once for DragAndDrop on dynamic categories
        // (legacy LibraryPresenter:715-717). For non-dynamic the map is unused.
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
        items: List<LibraryItem.Manga>,
        category: Category,
        libraryDefaultMode: LibrarySort,
        libraryDefaultAscending: Boolean,
        randomSeed: Long,
        removeArticles: Boolean,
        categoryOrderMap: Map<Int, Int>,
    ): List<LibraryItem.Manga> {
        if (items.size <= 1) return items

        // Implicit drag-and-drop fallback: category has no explicit mode, but the user has
        // dragged items around so `mangaOrder` is populated. Legacy LibraryPresenter:729-739.
        if (category.mangaSort == null && category.mangaOrder.isNotEmpty()) {
            val positionMap = category.mangaOrder.withIndex()
                .associate { (idx, id) -> id to idx }
            return items.sortedWith(mangaOrderFallbackComparator(positionMap, removeArticles))
        }

        val (mode, ascending) = resolveSortMode(
            category = category,
            libraryDefaultMode = libraryDefaultMode,
            libraryDefaultAscending = libraryDefaultAscending,
        )

        if (mode == LibrarySort.Random) {
            // Stable shuffle: same seed produces the same order across reloads until the user
            // re-rolls. Title tiebreaker not applied (a randomised list has no semantic ties).
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
        category: Category,
        libraryDefaultMode: LibrarySort,
        libraryDefaultAscending: Boolean,
    ): Pair<LibrarySort, Boolean> {
        if (category.mangaSort != null) {
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
    ): Comparator<LibraryItem.Manga> {
        val primary = Comparator<LibraryItem.Manga> { a, b ->
            primaryCompare(a, b, mode, ascending, removeArticles, isDynamic, categoryOrderMap)
        }
        // Title tiebreaker mirrors legacy LibraryPresenter:742-744; always ascending so a tie
        // produces deterministic ordering. Honors removeArticles.
        return primary.thenComparator { a, b -> titleCompare(a, b, removeArticles) }
    }

    private fun primaryCompare(
        a: LibraryItem.Manga,
        b: LibraryItem.Manga,
        mode: LibrarySort,
        ascending: Boolean,
        removeArticles: Boolean,
        isDynamic: Boolean,
        categoryOrderMap: Map<Int, Int>,
    ): Int {
        val aLm = a.libraryManga
        val bLm = b.libraryManga
        return when (mode) {
            LibrarySort.Title -> {
                val cmp = titleCompare(a, b, removeArticles)
                if (ascending) cmp else -cmp
            }
            LibrarySort.LatestChapter -> directional(ascending) {
                aLm.latestUpdate.compareTo(bLm.latestUpdate)
            }
            LibrarySort.Unread -> {
                // 0-unread sinks to the bottom regardless of direction; legacy quirk preserved
                // from LibraryPresenter:697-702 combined with the line 726 sign flip.
                when {
                    aLm.unread == 0 && bLm.unread == 0 -> 0
                    aLm.unread == 0 -> 1
                    bLm.unread == 0 -> -1
                    ascending -> aLm.unread.compareTo(bLm.unread)
                    else -> bLm.unread.compareTo(aLm.unread)
                }
            }
            LibrarySort.LastRead -> directional(ascending) {
                aLm.lastRead.compareTo(bLm.lastRead)
            }
            LibrarySort.TotalChapters -> directional(ascending) {
                aLm.totalChapters.compareTo(bLm.totalChapters)
            }
            LibrarySort.DateFetched -> directional(ascending) {
                aLm.lastFetch.compareTo(bLm.lastFetch)
            }
            LibrarySort.DateAdded -> directional(ascending) {
                aLm.manga.date_added.compareTo(bLm.manga.date_added)
            }
            LibrarySort.DragAndDrop -> {
                if (isDynamic) {
                    // Dynamic category: sort by the manga's original-category order. Legacy
                    // LibraryPresenter:715-717.
                    val aOrder = categoryOrderMap[aLm.category] ?: 0
                    val bOrder = categoryOrderMap[bLm.category] ?: 0
                    directional(ascending) { aOrder.compareTo(bOrder) }
                } else {
                    // Legacy LibraryPresenter:719 returns 0 here so the title tiebreaker takes
                    // over. The real drag-and-drop position is consulted only in the
                    // mangaOrder-fallback branch in sortCategory().
                    0
                }
            }
            LibrarySort.Random -> 0 // handled in sortCategory; never reached via comparator
        }
    }

    private inline fun directional(ascending: Boolean, block: () -> Int): Int {
        val cmp = block()
        return if (ascending) cmp else -cmp
    }

    private fun titleCompare(
        a: LibraryItem.Manga,
        b: LibraryItem.Manga,
        removeArticles: Boolean,
    ): Int {
        val aTitle = a.libraryManga.manga.title
        val bTitle = b.libraryManga.manga.title
        return if (removeArticles) {
            aTitle.removeArticles().compareTo(bTitle.removeArticles(), ignoreCase = true)
        } else {
            aTitle.compareTo(bTitle, ignoreCase = true)
        }
    }

    private fun mangaOrderFallbackComparator(
        positionMap: Map<Long, Int>,
        removeArticles: Boolean,
    ): Comparator<LibraryItem.Manga> {
        // Items missing from the order map sort first (idx = MIN_VALUE), matching legacy
        // LibraryPresenter:735 where `index1 == -1` returns -1. Identical positions or two
        // unknowns fall through to the title tiebreaker for deterministic ordering.
        return Comparator<LibraryItem.Manga> { a, b ->
            val aIdx = a.libraryManga.manga.id?.let { positionMap[it] } ?: Int.MIN_VALUE
            val bIdx = b.libraryManga.manga.id?.let { positionMap[it] } ?: Int.MIN_VALUE
            aIdx.compareTo(bIdx)
        }.thenComparator { a, b -> titleCompare(a, b, removeArticles) }
    }
}
