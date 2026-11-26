package reikai.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.library.components.GlobalSearchItem
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId
import reikai.presentation.browse.catalogue.AdaptiveGridMinCellWidth
import reikai.presentation.components.LibraryUpdatePullRefresh
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.presentation.core.util.plus

/**
 * Whether a section is collapsed. The two kinds of bucket use separate preferences. Runs per bucket per
 * frame, so [collapsedDynamicCategories] comes in normalized ([normalizedDynamicKeys]), as
 * [ReikaiLibraryState] carries it.
 */
fun reikaiIsCollapsed(
    bucket: LibraryBucket,
    collapsedCategories: Set<String>,
    collapsedDynamicCategories: Set<String>,
): Boolean = when (bucket) {
    is LibraryBucket.Real -> bucket.key in collapsedCategories
    is LibraryBucket.Dynamic -> bucket.key in collapsedDynamicCategories
}

/** One run of lazy items in [ReikaiLibraryContent]'s grid, in the order the grid emits them. */
sealed interface ReikaiGridSection {
    /** How many lazy items this run emits. */
    val itemCount: Int

    data object Search : ReikaiGridSection {
        override val itemCount = 1
    }

    data class Header(val bucket: LibraryBucket, val isCollapsed: Boolean) : ReikaiGridSection {
        override val itemCount = 1
    }

    data class Cells(val bucket: LibraryBucket, val items: List<LibraryItem>) : ReikaiGridSection {
        override val itemCount get() = items.size
    }
}

/**
 * The single list's item layout, which the grid builds from and the hopper, picker and fast-scroll thumb
 * index into, so the three cannot disagree: the search item, then per bucket a header and, unless
 * collapsed, its cells. List mode renders as a 1-column grid, so the layout is the same in every mode.
 */
fun reikaiGridSections(
    buckets: List<LibraryBucket>,
    hasSearchItem: Boolean,
    isCollapsed: (LibraryBucket) -> Boolean,
    itemsOf: (LibraryBucket) -> List<LibraryItem>,
): List<ReikaiGridSection> = buildList {
    if (hasSearchItem) add(ReikaiGridSection.Search)
    buckets.forEach { bucket ->
        val collapsed = isCollapsed(bucket)
        add(ReikaiGridSection.Header(bucket, collapsed))
        if (!collapsed) add(ReikaiGridSection.Cells(bucket, itemsOf(bucket)))
    }
}

/** Lazy item index of each bucket's header in [sections], so the hopper and picker can scroll to it. */
fun reikaiCategoryHeaderIndices(sections: List<ReikaiGridSection>): List<Int> {
    var index = 0
    return sections.mapNotNull { section ->
        val start = index
        index += section.itemCount
        start.takeIf { section is ReikaiGridSection.Header }
    }
}

/**
 * Lazy item index that begins each visual row of [sections], a cell run chunked into rows of [columns];
 * the list length is the total row count. Lets [ReikaiFastScrollLazyVerticalGrid] position its thumb by
 * row proportion (and map a drag back to an item) without averaging row heights.
 */
fun reikaiRowStartIndices(sections: List<ReikaiGridSection>, columns: Int): List<Int> {
    val cols = columns.coerceAtLeast(1)
    val rows = mutableListOf<Int>()
    var index = 0
    sections.forEach { section ->
        when (section) {
            is ReikaiGridSection.Cells -> (0 until section.itemCount step cols).mapTo(rows) { index + it }
            else -> rows += index
        }
        index += section.itemCount
    }
    return rows
}

/**
 * Reikai's single-list library renderer: every category is a section in one scrolling grid with a
 * collapsible header. Honors Mihon's global display mode ([displayMode]) by switching the per-cell
 * composable, reusing Mihon's grid cell and badge composables. List mode renders as a 1-column grid so
 * the hopper's single [gridState] keeps working across every mode; the hopper and picker are hosted by
 * the tab, so they overlay both this and Mihon's pager. [columns] is Mihon's column preference
 * (0 = adaptive), resolved here so the row structure is known for the fast-scroller.
 */
@Composable
fun ReikaiLibraryContent(
    buckets: List<LibraryBucket>,
    // Read twice per bucket per pass, which is cheap only because [LibraryAssembled.itemsFor] memoizes.
    getItemsForCategory: (LibraryBucket) -> List<LibraryItem>,
    collapsedCategories: Set<String>,
    collapsedDynamicCategories: Set<String>,
    // The assembly's count rule (null hides it), so a header follows the pager tabs while searching.
    getItemCount: (LibraryBucket) -> Int?,
    displayMode: LibraryDisplayMode,
    columns: Int,
    selection: Set<EntryId>,
    searchQuery: String?,
    gridState: LazyGridState,
    contentPadding: PaddingValues,
    onClickManga: (LibraryBucket, LibraryItem) -> Unit,
    onLongClickManga: (LibraryBucket, LibraryItem) -> Unit,
    onToggleDefaultCollapse: (String) -> Unit,
    onToggleDynamicCollapse: (String) -> Unit,
    onGlobalSearchClicked: () -> Unit,
    // pull down at the top of the single-list to update the whole library (overflow Update library).
    onRefresh: suspend () -> Boolean,
    refreshing: Boolean,
    // per-category header affordances; a dynamic group has no category, so these take the real one
    onClickCategorySort: (Category) -> Unit,
    onRefreshCategory: (Category) -> Unit,
    onSelectAllInCategory: (LibraryBucket) -> Unit,
    // The effective sort per category (its override, or the global sort it follows): the header label via
    // [sortLabelFor] and the arrow direction via [sortAscendingFor].
    sortLabelFor: (Category) -> StringResource,
    sortAscendingFor: (Category) -> Boolean?,
    // continue-reading button on covers, single-list parity with the pager; null = hidden
    onClickContinueReading: ((LibraryItem) -> Unit)? = null,
    // a Manual category's drag, else null; each section reorders only within itself
    manualDragFor: (LibraryBucket) -> ManualOrderDrag? = { null },
) {
    val isList = displayMode is LibraryDisplayMode.List
    // Mode-qualified so Compose doesn't recycle a list-row slot as a grid cell when the mode flips.
    val cellContentType = "reikai_cell_${displayMode.serialize()}"
    val gridPadding = contentPadding + if (isList) PaddingValues(0.dp) else PaddingValues(8.dp)

    BoxWithConstraints {
        val density = LocalDensity.current
        // Resolve the column count up front (also lets us drive the grid with Fixed, so it matches
        // the row structure we hand the fast-scroller). 0 means adaptive: mirror GridCells.Adaptive.
        val columnCount = when {
            isList -> 1
            columns > 0 -> columns
            else -> with(density) {
                val spacingPx = CommonMangaItemDefaults.GridHorizontalSpacer.roundToPx()
                val minSizePx = AdaptiveGridMinCellWidth.roundToPx()
                val horizontalPaddingPx = gridPadding.calculateStartPadding(LayoutDirection.Ltr).roundToPx() +
                    gridPadding.calculateEndPadding(LayoutDirection.Ltr).roundToPx()
                val availablePx = constraints.maxWidth - horizontalPaddingPx
                ((availablePx + spacingPx) / (minSizePx + spacingPx)).coerceAtLeast(1)
            }
        }

        // Walks every bucket's items, so only what it reads rebuilds it, not every recomposition.
        val hasSearchItem = !searchQuery.isNullOrEmpty()
        val sections = remember(
            buckets,
            hasSearchItem,
            collapsedCategories,
            collapsedDynamicCategories,
            getItemsForCategory,
        ) {
            reikaiGridSections(
                buckets = buckets,
                hasSearchItem = hasSearchItem,
                isCollapsed = { reikaiIsCollapsed(it, collapsedCategories, collapsedDynamicCategories) },
                itemsOf = getItemsForCategory,
            )
        }
        val rowStartIndices = remember(sections, columnCount) { reikaiRowStartIndices(sections, columnCount) }

        val manualOrder = rememberManualOrder()
        val reorder = rememberManualOrderGrid(gridState, manualOrder) { section ->
            buckets.find { it.key == section }?.let(getItemsForCategory).orEmpty()
        }

        LibraryUpdatePullRefresh(
            updating = refreshing,
            enabled = selection.isEmpty(),
            onRefresh = onRefresh,
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
                verticalArrangement = Arrangement.spacedBy(
                    if (isList) 0.dp else CommonMangaItemDefaults.GridVerticalSpacer,
                ),
                horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
            ) {
                sections.forEach { section ->
                    when (section) {
                        ReikaiGridSection.Search -> item(
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "reikai_global_search",
                        ) {
                            GlobalSearchItem(searchQuery = searchQuery.orEmpty(), onClick = onGlobalSearchClicked)
                        }
                        is ReikaiGridSection.Header -> {
                            val bucket = section.bucket
                            // Null for a dynamic group, which is what turns the sort / refresh affordances off.
                            val category = bucket.realCategory
                            val items = getItemsForCategory(bucket)
                            val itemCount = getItemCount(bucket)
                            item(
                                span = { GridItemSpan(maxLineSpan) },
                                key = "reikai_header_${bucket.key}",
                                contentType = "reikai_header",
                            ) {
                                ReikaiLibraryCategoryHeader(
                                    name = bucket.visualLabel,
                                    itemCount = itemCount ?: 0,
                                    showItemCount = itemCount != null,
                                    isCollapsed = section.isCollapsed,
                                    onClick = {
                                        if (category != null) {
                                            onToggleDefaultCollapse(bucket.key)
                                        } else {
                                            onToggleDynamicCollapse(bucket.key)
                                        }
                                    },
                                    selectionMode = selection.isNotEmpty(),
                                    allSelected = items.isNotEmpty() && items.all { it.entryId in selection },
                                    onToggleSelectAll = { onSelectAllInCategory(bucket) },
                                    sortLabel = category?.let(sortLabelFor),
                                    sortAscending = category?.let(sortAscendingFor),
                                    onClickSort = category?.let { { onClickCategorySort(it) } },
                                    onClickRefresh = category?.let { { onRefreshCategory(it) } },
                                )
                            }
                        }
                        // Keyed by bucket and type (manualOrderKey): an entry can belong to several buckets,
                        // and a manga and a novel can share a row id.
                        is ReikaiGridSection.Cells -> libraryCells(
                            section = section.bucket.key,
                            rows = section.items,
                            contentType = cellContentType,
                            drag = manualDragFor(section.bucket),
                            manualOrder = manualOrder,
                            reorder = reorder,
                            onLongClick = { onLongClickManga(section.bucket, it) },
                            cell = libraryItemCell(
                                displayMode = displayMode,
                                selection = selection,
                                onClick = { onClickManga(section.bucket, it) },
                                onClickContinueReading = onClickContinueReading,
                            ),
                        )
                    }
                }
            }
        }
    }
}
