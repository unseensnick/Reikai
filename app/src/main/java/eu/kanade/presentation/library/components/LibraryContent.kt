package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.launch
import reikai.domain.entry.EntryId // RK
import reikai.presentation.components.LibraryUpdatePullRefresh // RK
import reikai.presentation.library.LibraryBucket // RK
import tachiyomi.domain.library.model.LibraryDisplayMode

@Composable
fun LibraryContent(
    // RK: a page is one section of the assembled library, which is a category or a dynamic group
    buckets: List<LibraryBucket>,
    searchQuery: String?,
    selection: Set<EntryId>, // RK: neutral identity, a manga and a novel can share a row id
    contentPadding: PaddingValues,
    // RK: pagerState hoisted to the caller so the category hopper can drive it directly
    pagerState: PagerState,
    hasActiveFilters: Boolean,
    showPageTabs: Boolean,
    onChangeCurrentPage: (Int) -> Unit,
    // RK --> retyped to LibraryBucket, LibraryItem and EntryId in place of Category, LibraryManga and Long
    onClickManga: (EntryId) -> Unit,
    onContinueReadingClicked: ((LibraryItem) -> Unit)?,
    onToggleSelection: (LibraryBucket, LibraryItem) -> Unit,
    onToggleRangeSelection: (LibraryBucket, LibraryItem) -> Unit,
    onRefresh: () -> Boolean,
    refreshing: Boolean,
    onGlobalSearchClicked: () -> Unit,
    getItemCountForCategory: (LibraryBucket) -> Int?,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getItemsForCategory: (LibraryBucket) -> List<LibraryItem>,
    // RK <--
) {
    Column(
        modifier = Modifier.padding(
            top = contentPadding.calculateTopPadding(),
            start = contentPadding.calculateStartPadding(LocalLayoutDirection.current),
            end = contentPadding.calculateEndPadding(LocalLayoutDirection.current),
        ),
    ) {
        val scope = rememberCoroutineScope()

        // RK: a lone dynamic group still gets tabs; only a lone real Default category hides them.
        val onlySystemCategory = buckets.size == 1 && buckets.first().realCategory?.isSystemCategory == true
        if (showPageTabs && buckets.isNotEmpty() && !onlySystemCategory) {
            LaunchedEffect(buckets) {
                if (buckets.size <= pagerState.currentPage) {
                    pagerState.scrollToPage(buckets.size - 1)
                }
            }
            LibraryTabs(
                buckets = buckets, // RK: sections of the assembled library
                pagerState = pagerState,
                getItemCountForCategory = getItemCountForCategory,
                onTabItemClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(it)
                    }
                },
            )
        }

        // RK: the spinner follows the update job instead of upstream's one-second fake
        LibraryUpdatePullRefresh(
            updating = refreshing,
            enabled = selection.isEmpty(),
            onRefresh = onRefresh,
        ) {
            LibraryPager(
                state = pagerState,
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                hasActiveFilters = hasActiveFilters,
                selection = selection,
                searchQuery = searchQuery,
                onGlobalSearchClicked = onGlobalSearchClicked,
                getCategoryForPage = { page -> buckets[page] }, // RK: a page is a bucket
                getDisplayMode = getDisplayMode,
                getColumnsForOrientation = getColumnsForOrientation,
                getItemsForCategory = getItemsForCategory,
                // RK --> a LibraryItem row, clicked by its neutral identity so a novel routes to its own screen
                onClickManga = { category, item ->
                    if (selection.isNotEmpty()) {
                        onToggleSelection(category, item)
                    } else {
                        onClickManga(item.entryId)
                    }
                },
                // RK <--
                onLongClickManga = onToggleRangeSelection,
                onClickContinueReading = onContinueReadingClicked,
            )
        }

        // RK --> report only the settled page so external category jumps don't fight mid-swipe
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }.collect { onChangeCurrentPage(it) }
        }
        // RK <--
    }
}
