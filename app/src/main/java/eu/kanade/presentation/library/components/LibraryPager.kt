package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.tachiyomi.ui.library.LibraryItem
import reikai.domain.entry.EntryId // RK
import reikai.presentation.library.LibraryBucket // RK
import reikai.presentation.library.LibraryItemsLayout // RK
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.util.plus

@Composable
fun LibraryPager(
    state: PagerState,
    contentPadding: PaddingValues,
    hasActiveFilters: Boolean,
    selection: Set<EntryId>, // RK: neutral identity, a manga and a novel can share a row id
    searchQuery: String?,
    onGlobalSearchClicked: () -> Unit,
    // RK: a page is one section of the assembled library, which is a category or a dynamic group
    getCategoryForPage: (Int) -> LibraryBucket,
    getDisplayMode: (Int) -> PreferenceMutableState<LibraryDisplayMode>,
    getColumnsForOrientation: (Boolean) -> PreferenceMutableState<Int>,
    getItemsForCategory: (LibraryBucket) -> List<LibraryItem>,
    onClickManga: (LibraryBucket, LibraryItem) -> Unit,
    onLongClickManga: (LibraryBucket, LibraryItem) -> Unit,
    onClickContinueReading: ((LibraryItem) -> Unit)?,
) {
    HorizontalPager(
        modifier = Modifier.fillMaxSize(),
        state = state,
        verticalAlignment = Alignment.Top,
    ) { page ->
        if (page !in ((state.currentPage - 1)..(state.currentPage + 1))) {
            // To make sure only one offscreen page is being composed
            return@HorizontalPager
        }
        val bucket = getCategoryForPage(page) // RK
        val items = getItemsForCategory(bucket)

        if (items.isEmpty()) {
            LibraryPagerEmptyScreen(
                searchQuery = searchQuery,
                hasActiveFilters = hasActiveFilters,
                contentPadding = contentPadding,
                onGlobalSearchClicked = onGlobalSearchClicked,
            )
            return@HorizontalPager
        }

        val displayMode by getDisplayMode(page)
        // RK --> the column count and the mode switch moved to LibraryItemsLayout, which a Statistics
        //     row's series list draws through too. Rows are neutral LibraryItems in a LibraryBucket.
        LibraryItemsLayout(
            items = items,
            displayMode = displayMode,
            getColumnsForOrientation = getColumnsForOrientation,
            contentPadding = contentPadding,
            selection = selection,
            onClick = { onClickManga(bucket, it) },
            onLongClick = { onLongClickManga(bucket, it) },
            onClickContinueReading = onClickContinueReading,
            searchQuery = searchQuery,
            onGlobalSearchClicked = onGlobalSearchClicked,
        )
        // RK <--
    }
}

@Composable
private fun LibraryPagerEmptyScreen(
    searchQuery: String?,
    hasActiveFilters: Boolean,
    contentPadding: PaddingValues,
    onGlobalSearchClicked: () -> Unit,
) {
    val msg = when {
        !searchQuery.isNullOrEmpty() -> MR.strings.no_results_found
        hasActiveFilters -> MR.strings.error_no_match
        else -> MR.strings.information_no_manga_category
    }

    Column(
        modifier = Modifier
            .padding(contentPadding + PaddingValues(8.dp))
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        if (!searchQuery.isNullOrEmpty()) {
            GlobalSearchItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                searchQuery = searchQuery,
                onClick = onGlobalSearchClicked,
            )
        }

        EmptyScreen(
            stringRes = msg,
            modifier = Modifier.weight(1f),
        )
    }
}
