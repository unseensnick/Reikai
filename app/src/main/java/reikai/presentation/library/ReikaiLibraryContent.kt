package reikai.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.library.components.DownloadsBadge
import eu.kanade.presentation.library.components.GlobalSearchItem
import eu.kanade.presentation.library.components.LanguageBadge
import eu.kanade.presentation.library.components.MangaCompactGridItem
import eu.kanade.presentation.library.components.UnreadBadge
import eu.kanade.tachiyomi.ui.library.LibraryItem
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.presentation.core.components.FastScrollLazyVerticalGrid
import tachiyomi.presentation.core.util.plus

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
 * picker can scroll to a category.
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
 * Reikai's single-list library renderer (Y1): every category is a section in one scrolling grid
 * with a collapsible header. Reuses Mihon's grid cell and badge composables. The hopper and picker
 * are hosted by the library tab (so they overlay both this and Mihon's pager); [gridState] is
 * hoisted in for them to drive. Staggered grid and list layout layer on later.
 */
@Composable
fun ReikaiLibraryContent(
    categories: List<Category>,
    getItemsForCategory: (Category) -> List<LibraryItem>,
    collapsedCategories: Set<String>,
    collapsedDynamicCategories: Set<String>,
    showItemCounts: Boolean,
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
) {
    FastScrollLazyVerticalGrid(
        columns = if (columns == 0) GridCells.Adaptive(128.dp) else GridCells.Fixed(columns),
        state = gridState,
        contentPadding = contentPadding + PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
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
            val items = getItemsForCategory(category)

            item(
                span = { GridItemSpan(maxLineSpan) },
                key = "reikai_header_${category.id}",
                contentType = "reikai_header",
            ) {
                ReikaiLibraryCategoryHeader(
                    name = if (dynamic) ReikaiDynamicCategory.displayName(category) else category.name,
                    itemCount = items.size,
                    showItemCount = showItemCounts,
                    isCollapsed = collapsed,
                    onClick = { if (dynamic) onToggleDynamicCollapse(headerKey) else onToggleDefaultCollapse(headerKey) },
                )
            }

            if (!collapsed) {
                items(
                    items = items,
                    // A manga can belong to several categories, so qualify the key by category.
                    key = { "reikai_cell_${category.id}_${it.id}" },
                    contentType = { "reikai_cell" },
                ) { libraryItem ->
                    val manga = libraryItem.libraryManga.manga
                    MangaCompactGridItem(
                        isSelected = manga.id in selection,
                        title = manga.title,
                        coverData = MangaCover(
                            mangaId = manga.id,
                            sourceId = manga.source,
                            isMangaFavorite = manga.favorite,
                            url = manga.thumbnailUrl,
                            lastModified = manga.coverLastModified,
                        ),
                        coverBadgeStart = {
                            DownloadsBadge(count = libraryItem.badges.downloadCount)
                            UnreadBadge(count = libraryItem.badges.unreadCount)
                        },
                        coverBadgeEnd = {
                            LanguageBadge(
                                isLocal = libraryItem.badges.isLocal,
                                sourceLanguage = libraryItem.badges.sourceLanguage,
                            )
                        },
                        onClick = { onClickManga(category, libraryItem.libraryManga) },
                        onLongClick = { onLongClickManga(category, libraryItem.libraryManga) },
                    )
                }
            }
        }
    }
}
