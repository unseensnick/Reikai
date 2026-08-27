package eu.kanade.presentation.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SearchToolbar
import reikai.presentation.browse.EntrySearchSourceFilterChips
import reikai.presentation.browse.components.BulkSelectionToolbar
import reikai.presentation.browse.globalsearch.SearchSourceFilter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun GlobalSearchToolbar(
    searchQuery: String?,
    progress: Int,
    total: Int,
    navigateUp: () -> Unit,
    onChangeSearchQuery: (String?) -> Unit,
    onSearch: (String) -> Unit,
    hideSourceFilter: Boolean,
    sourceFilter: SearchSourceFilter,
    onChangeSearchFilter: (SearchSourceFilter) -> Unit,
    onlyShowHasResults: Boolean,
    onToggleResults: () -> Unit,
    // RK: null keeps the bar on plain `surface` instead of lerping to the scrolled container tint,
    //      so the bar, the tab strip and the chip row read as one header block.
    scrollBehavior: TopAppBarScrollBehavior? = null,
    // RK: bulk-selection. onToggleSelectionMode present -> show the Select action.
    onToggleSelectionMode: (() -> Unit)? = null,
    selectionMode: Boolean = false,
    selectedCount: Int = 0,
    // RK: overrides the plain count, so a selection spanning both content types can say how much of
    //      each kind it holds.
    selectionTitle: String? = null,
    onClickClearSelection: () -> Unit = {},
    onChangeCategoryClick: () -> Unit = {},
    // RK: the shared screen puts its content-type tab strip here, above the source-filter chips, so
    //      the two controls read as different things rather than as two rows of chips.
    tabs: @Composable () -> Unit = {},
) {
    Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
        Box {
            // RK: selection bar replaces the search field while bulk-selecting; the filter chips
            //     below stay put (consistent with the per-source browse screen).
            if (selectionMode) {
                BulkSelectionToolbar(
                    selectedCount = selectedCount,
                    title = selectionTitle,
                    onClickClearSelection = onClickClearSelection,
                    onChangeCategoryClick = onChangeCategoryClick,
                )
            } else {
                SearchToolbar(
                    searchQuery = searchQuery,
                    onChangeSearchQuery = onChangeSearchQuery,
                    onSearch = onSearch,
                    onClickCloseSearch = navigateUp,
                    navigateUp = navigateUp,
                    scrollBehavior = scrollBehavior,
                    // RK: bulk-select entry
                    actions = {
                        if (onToggleSelectionMode != null) {
                            AppBarActions(
                                buildList {
                                    add(
                                        AppBar.Action(
                                            title = stringResource(MR.strings.action_bulk_select),
                                            icon = Icons.Outlined.Checklist,
                                            onClick = onToggleSelectionMode,
                                        ),
                                    )
                                },
                            )
                        }
                    },
                )
            }
            if (progress in 1..<total) {
                LinearProgressIndicator(
                    progress = { progress / total.toFloat() },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(),
                )
            }
        }

        tabs()

        // RK: the filter chips moved to the shared reikai.presentation.browse.EntrySearchSourceFilterChips
        // (manga + novel global search share them). Driven by primitives, not the SearchSourceFilter enum.
        EntrySearchSourceFilterChips(
            isPinnedOnly = sourceFilter == SearchSourceFilter.PinnedOnly,
            onlyShowHasResults = onlyShowHasResults,
            showSourceFilter = !hideSourceFilter,
            onSelectPinnedOnly = { onChangeSearchFilter(SearchSourceFilter.PinnedOnly) },
            onSelectAll = { onChangeSearchFilter(SearchSourceFilter.All) },
            onToggleResults = onToggleResults,
        )
    }
}
