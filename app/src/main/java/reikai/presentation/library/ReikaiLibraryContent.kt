package reikai.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.library.components.DownloadsBadge
import eu.kanade.presentation.library.components.GlobalSearchItem
import eu.kanade.presentation.library.components.LanguageBadge
import eu.kanade.presentation.library.components.MangaComfortableGridItem
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.MangaListItem
import eu.kanade.presentation.library.components.UnreadBadge
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.sort
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.util.plus
import kotlin.time.Duration.Companion.seconds

/** Whether a category section is collapsed (real categories keyed by id, dynamic ones by header key). */
fun reikaiIsCollapsed(
    category: Category,
    collapsedCategories: Set<String>,
    collapsedDynamicCategories: Set<String>,
): Boolean {
    return if (ReikaiDynamicCategory.isDynamic(category)) {
        ReikaiDynamicCategory.headerKey(category) in collapsedDynamicCategories
    } else {
        category.id.toString() in collapsedCategories
    }
}

/**
 * Lazy item index of each category header in [ReikaiLibraryContent]'s grid (1 header + its cells
 * per non-collapsed category). Kept in lock-step with the grid building below so the hopper /
 * picker can scroll to a category. Layout-agnostic: List mode renders as a 1-column grid, so the
 * item count per category is unchanged.
 */
fun reikaiCategoryHeaderIndices(
    categories: List<Category>,
    hasSearchItem: Boolean,
    isCollapsed: (Category) -> Boolean,
    itemCount: (Category) -> Int,
): List<Int> {
    var index = if (hasSearchItem) 1 else 0
    return categories.map { category ->
        val headerIndex = index
        index += 1
        if (!isCollapsed(category)) index += itemCount(category)
        headerIndex
    }
}

/**
 * Grid item index that begins each visual row, in grid order: the search item (if any), then per
 * category a header row plus, when expanded, its cells chunked into rows of [columns]. The list
 * length is the total row count. Lets [ReikaiFastScrollLazyVerticalGrid] position its thumb by row
 * proportion (and map a drag back to an item) without averaging row heights. Stays in lock-step with
 * the grid building below and with [reikaiCategoryHeaderIndices].
 */
fun reikaiRowStartIndices(
    categories: List<Category>,
    hasSearchItem: Boolean,
    isCollapsed: (Category) -> Boolean,
    itemCount: (Category) -> Int,
    columns: Int,
): List<Int> {
    val cols = columns.coerceAtLeast(1)
    val rows = mutableListOf<Int>()
    var item = 0
    if (hasSearchItem) {
        rows.add(item)
        item += 1
    }
    categories.forEach { category ->
        rows.add(item)
        item += 1
        if (!isCollapsed(category)) {
            val count = itemCount(category)
            var offset = 0
            while (offset < count) {
                rows.add(item + offset)
                offset += cols
            }
            item += count
        }
    }
    return rows
}

/**
 * Reikai's single-list library renderer (Y1): every category is a section in one scrolling grid
 * with a collapsible header. Honors Mihon's global display mode ([displayMode]) by switching the
 * per-cell composable, reusing Mihon's grid cell and badge composables. List mode renders as a
 * 1-column grid so the hopper's single [gridState] keeps working across every mode. The hopper and
 * picker are hosted by the library tab (so they overlay both this and Mihon's pager).
 *
 * [columns] is Mihon's column preference (0 = adaptive). It is resolved here to a concrete count so
 * the row structure is known (for the fast-scroller) and the grid uses exactly that many columns.
 */
@Composable
fun ReikaiLibraryContent(
    categories: List<Category>,
    getItemsForCategory: (Category) -> List<LibraryItem>,
    collapsedCategories: Set<String>,
    collapsedDynamicCategories: Set<String>,
    showItemCounts: Boolean,
    displayMode: LibraryDisplayMode,
    columns: Int,
    selection: Set<Long>,
    searchQuery: String?,
    gridState: LazyGridState,
    contentPadding: PaddingValues,
    onClickManga: (Category, LibraryManga) -> Unit,
    onLongClickManga: (Category, LibraryManga) -> Unit,
    onToggleDefaultCollapse: (String) -> Unit,
    onToggleDynamicCollapse: (String) -> Unit,
    onGlobalSearchClicked: () -> Unit,
    // pull down at the top of the single-list to update the whole library (overflow Update library).
    onRefresh: () -> Boolean,
    // per-category header affordances (real categories only; dynamic groups opt out)
    onClickCategorySort: (Category) -> Unit,
    onRefreshCategory: (Category) -> Unit,
    onSelectAllInCategory: (Category) -> Unit,
    // Novel-correct sort label for a category's header (its stored sort enum diverges from manga's on
    // two bits); null falls back to the manga-enum decode in the header.
    sortLabelFor: ((Category) -> StringResource?)? = null,
    // continue-reading button on covers, single-list parity with the pager; null = hidden
    onClickContinueReading: ((LibraryManga) -> Unit)? = null,
) {
    val isList = displayMode is LibraryDisplayMode.List
    // Mode-qualified so Compose doesn't recycle a list-row slot as a grid cell when the mode flips.
    val cellContentType = "reikai_cell_${displayMode.serialize()}"
    val gridPadding = contentPadding + if (isList) PaddingValues(0.dp) else PaddingValues(8.dp)
    val cellSpacing = 4.dp

    BoxWithConstraints {
        val density = LocalDensity.current
        // Resolve the column count up front (also lets us drive the grid with Fixed, so it matches
        // the row structure we hand the fast-scroller). 0 means adaptive: mirror GridCells.Adaptive.
        val columnCount = when {
            isList -> 1
            columns > 0 -> columns
            else -> with(density) {
                val spacingPx = cellSpacing.roundToPx()
                val minSizePx = 128.dp.roundToPx()
                val horizontalPaddingPx = gridPadding.calculateStartPadding(LayoutDirection.Ltr).roundToPx() +
                    gridPadding.calculateEndPadding(LayoutDirection.Ltr).roundToPx()
                val availablePx = constraints.maxWidth - horizontalPaddingPx
                ((availablePx + spacingPx) / (minSizePx + spacingPx)).coerceAtLeast(1)
            }
        }

        // Resolve each category's items once per pass and share it: the row-index math needs the
        // sizes and the grid builder needs the lists, so calling getItemsForCategory in both spots
        // walked every category's items twice per recomposition. Not remembered across recompositions
        // on purpose, the lambda reads live library state, so a cached map could serve stale badges.
        val itemsByCategory = categories.associateWith(getItemsForCategory)

        val rowStartIndices = reikaiRowStartIndices(
            categories = categories,
            hasSearchItem = !searchQuery.isNullOrEmpty(),
            isCollapsed = { reikaiIsCollapsed(it, collapsedCategories, collapsedDynamicCategories) },
            itemCount = { itemsByCategory[it]?.size ?: 0 },
            columns = columnCount,
        )

        val scope = rememberCoroutineScope()
        var isRefreshing by remember { mutableStateOf(false) }
        PullRefresh(
            refreshing = isRefreshing,
            enabled = selection.isEmpty(),
            onRefresh = {
                val started = onRefresh()
                if (started) {
                    scope.launch {
                        // Cosmetic spinner only: the library update runs as a background job.
                        isRefreshing = true
                        delay(1.seconds)
                        isRefreshing = false
                    }
                }
            },
            indicatorPadding = PaddingValues(top = contentPadding.calculateTopPadding()),
        ) {
            ReikaiFastScrollLazyVerticalGrid(
                columns = GridCells.Fixed(columnCount),
                totalRows = rowStartIndices.size,
                itemIndexForRow = { rowStartIndices.getOrElse(it) { 0 } },
                state = gridState,
                contentPadding = gridPadding,
                // Inset the thumb track to the visible scroll area so it isn't hidden behind the top bar
                // or bottom nav.
                topContentPadding = contentPadding.calculateTopPadding(),
                bottomContentPadding = contentPadding.calculateBottomPadding(),
                endContentPadding = contentPadding.calculateEndPadding(LayoutDirection.Ltr),
                verticalArrangement = Arrangement.spacedBy(if (isList) 0.dp else cellSpacing),
                horizontalArrangement = Arrangement.spacedBy(cellSpacing),
            ) {
                if (!searchQuery.isNullOrEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, contentType = "reikai_global_search") {
                        GlobalSearchItem(searchQuery = searchQuery, onClick = onGlobalSearchClicked)
                    }
                }

                categories.forEach { category ->
                    val dynamic = ReikaiDynamicCategory.isDynamic(category)
                    val headerKey = if (dynamic) ReikaiDynamicCategory.headerKey(category) else category.id.toString()
                    val collapsed = reikaiIsCollapsed(category, collapsedCategories, collapsedDynamicCategories)
                    val items = itemsByCategory[category].orEmpty()

                    item(
                        span = { GridItemSpan(maxLineSpan) },
                        key = "reikai_header_${category.id}",
                        contentType = "reikai_header",
                    ) {
                        ReikaiLibraryCategoryHeader(
                            name = if (dynamic) ReikaiDynamicCategory.displayName(category) else category.visualName,
                            itemCount = items.size,
                            showItemCount = showItemCounts,
                            isCollapsed = collapsed,
                            onClick = {
                                if (dynamic) onToggleDynamicCollapse(headerKey) else onToggleDefaultCollapse(headerKey)
                            },
                            selectionMode = selection.isNotEmpty(),
                            allSelected = items.isNotEmpty() && items.all { it.id in selection },
                            onToggleSelectAll = { onSelectAllInCategory(category) },
                            // Dynamic groups have no real category to sort/refresh.
                            sort = if (dynamic) null else category.sort,
                            sortLabel = if (dynamic) null else sortLabelFor?.invoke(category),
                            onClickSort = if (dynamic) null else { { onClickCategorySort(category) } },
                            onClickRefresh = if (dynamic) null else { { onRefreshCategory(category) } },
                        )
                    }

                    if (!collapsed) {
                        items(
                            items = items,
                            // A manga can belong to several categories, so qualify the key by category.
                            key = { "reikai_cell_${category.id}_${it.id}" },
                            contentType = { cellContentType },
                        ) { libraryItem ->
                            val manga = libraryItem.libraryManga.manga
                            val isSelected = manga.id in selection
                            val coverData = libraryCoverModel(libraryItem) // NovelCover for novels, else MangaCover
                            val onClick = { onClickManga(category, libraryItem.libraryManga) }
                            val onLongClick = { onLongClickManga(category, libraryItem.libraryManga) }
                            // Show the play button only when there's something unread (matches the pager).
                            val onContinueReading = if (onClickContinueReading != null && libraryItem.unreadCount > 0) {
                                { onClickContinueReading(libraryItem.libraryManga) }
                            } else {
                                null
                            }

                            when (displayMode) {
                                LibraryDisplayMode.List -> MangaListItem(
                                    coverData = coverData,
                                    title = manga.title,
                                    onClick = onClick,
                                    onLongClick = onLongClick,
                                    onClickContinueReading = onContinueReading,
                                    badge = {
                                        DownloadsBadge(count = libraryItem.badges.downloadCount)
                                        UnreadBadge(count = libraryItem.badges.unreadCount)
                                        LanguageBadge(
                                            isLocal = libraryItem.badges.isLocal,
                                            sourceLanguage = libraryItem.badges.sourceLanguage,
                                        )
                                        LibraryCoverEndBadge(libraryItem) // merge / novel-icon / manga-icon
                                    },
                                    isSelected = isSelected,
                                )
                                LibraryDisplayMode.ComfortableGrid -> MangaComfortableGridItem(
                                    coverData = coverData,
                                    title = manga.title,
                                    onClick = onClick,
                                    onLongClick = onLongClick,
                                    onClickContinueReading = onContinueReading,
                                    isSelected = isSelected,
                                    coverBadgeStart = {
                                        DownloadsBadge(count = libraryItem.badges.downloadCount)
                                        UnreadBadge(count = libraryItem.badges.unreadCount)
                                    },
                                    coverBadgeEnd = {
                                        LanguageBadge(
                                            isLocal = libraryItem.badges.isLocal,
                                            sourceLanguage = libraryItem.badges.sourceLanguage,
                                        )
                                        LibraryCoverEndBadge(libraryItem) // merge / novel-icon / manga-icon
                                    },
                                )
                                // Panorama: same uniform Book-height cell, wide covers shown whole (letterboxed).
                                LibraryDisplayMode.ComfortableGridPanorama -> ReikaiComfortableGridPanoramaItem(
                                    coverData = coverData,
                                    title = manga.title,
                                    onClick = onClick,
                                    onLongClick = onLongClick,
                                    onClickContinueReading = onContinueReading,
                                    isSelected = isSelected,
                                    coverBadgeStart = {
                                        DownloadsBadge(count = libraryItem.badges.downloadCount)
                                        UnreadBadge(count = libraryItem.badges.unreadCount)
                                    },
                                    coverBadgeEnd = {
                                        LanguageBadge(
                                            isLocal = libraryItem.badges.isLocal,
                                            sourceLanguage = libraryItem.badges.sourceLanguage,
                                        )
                                        LibraryCoverEndBadge(libraryItem) // merge / novel-icon / manga-icon
                                    },
                                )
                                // Compact grid (with title) and cover-only grid (title null) share a cell.
                                LibraryDisplayMode.CompactGrid,
                                LibraryDisplayMode.CoverOnlyGrid,
                                -> MangaCompactGridItem(
                                    coverData = coverData,
                                    title = manga.title.takeIf { displayMode is LibraryDisplayMode.CompactGrid },
                                    onClick = onClick,
                                    onLongClick = onLongClick,
                                    onClickContinueReading = onContinueReading,
                                    isSelected = isSelected,
                                    coverBadgeStart = {
                                        DownloadsBadge(count = libraryItem.badges.downloadCount)
                                        UnreadBadge(count = libraryItem.badges.unreadCount)
                                    },
                                    coverBadgeEnd = {
                                        LanguageBadge(
                                            isLocal = libraryItem.badges.isLocal,
                                            sourceLanguage = libraryItem.badges.sourceLanguage,
                                        )
                                        LibraryCoverEndBadge(libraryItem) // merge / novel-icon / manga-icon
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
