package exh.md.follows

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import kotlinx.serialization.Serializable
import mihon.core.navigation.LocalNavigator
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.SelectAll
import mihon.presentation.core.util.collectAsLazyPagingItems
import reikai.domain.source.SourceKey
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.EntryAddDialogs
import reikai.presentation.browse.PendingBulkCategoryDialog
import reikai.presentation.browse.catalogue.EntryBrowseCatalogue
import reikai.presentation.browse.catalogue.EntryBrowseRow
import reikai.presentation.browse.catalogue.EntryBrowseScreenState
import reikai.presentation.browse.catalogue.MangaBrowseAdapter
import reikai.presentation.browse.catalogue.SourceUnavailable
import reikai.presentation.browse.components.BulkSelectionToolbar
import reikai.presentation.browse.rememberEntryGestures
import reikai.presentation.details.rememberDetailsOpener
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Browse the signed-in user's MangaDex follows. Reuses the source-browse grid and favoriting flow
 * via [MangaDexFollowsViewModel], plus the shared bulk-selection toolbar for adding many at once.
 * Reached from the browse filter sheet's Follows button.
 */
@Serializable
data class MangaDexFollowsRoute(val sourceId: Long) : NavKey

@Composable
fun MangaDexFollowsScreen(sourceId: Long) {
    val navigator = LocalNavigator.current
    val opener = rememberDetailsOpener()
    val viewModel = assistedMetroViewModel<MangaDexFollowsViewModel, MangaDexFollowsViewModel.Factory> {
        create(sourceId = sourceId)
    }
    val snackbarHostState = remember { SnackbarHostState() }

    val bulkFavoriteViewModel = metroViewModel<BulkFavoriteViewModel>()
    val bulkFavoriteState by bulkFavoriteViewModel.state.collectAsState()
    val adapterScope = rememberCoroutineScope()
    val adapter = remember(viewModel, bulkFavoriteViewModel) {
        MangaBrowseAdapter(viewModel, bulkFavoriteViewModel, adapterScope)
    }
    val entries = adapter.rows.collectAsLazyPagingItems()
    val browseState by adapter.state.collectAsState()
    // A MangaDex extension that is gone or failed reads as such rather than spinning forever.
    val loaded = browseState as? EntryBrowseScreenState.Loaded
    if (loaded == null) {
        SourceUnavailable(browseState, navigator::pop)
        return
    }

    BackHandler(enabled = bulkFavoriteState.selectionMode) {
        bulkFavoriteViewModel.backHandler()
    }
    val gestures = rememberEntryGestures(
        choose = adapter::toggleSelection.takeIf { bulkFavoriteState.selectionMode },
        open = { row: EntryBrowseRow -> opener.open(row, SourceKey.Manga(sourceId)) },
        add = adapter::onRowLongClick,
    )

    Scaffold(
        topBar = { scrollBehavior ->
            if (bulkFavoriteState.selectionMode) {
                BulkSelectionToolbar(
                    selectedCount = bulkFavoriteState.selection.size,
                    onClickClearSelection = bulkFavoriteViewModel::toggleSelectionMode,
                    onChangeCategoryClick = bulkFavoriteViewModel::addFavorite,
                    onSelectAll = { adapter.selectAll(entries.itemSnapshotList.items) },
                    onReverseSelection = {
                        adapter.invertSelection(entries.itemSnapshotList.items)
                    },
                )
            } else {
                AppBar(
                    title = stringResource(MR.strings.mangadex_follows),
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            buildList {
                                add(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_bulk_select),
                                        icon = MaterialSymbols.Rounded.SelectAll,
                                        onClick = bulkFavoriteViewModel::toggleSelectionMode,
                                    ),
                                )
                            },
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
        EntryBrowseCatalogue(
            rows = entries,
            rowStyle = loaded.rowStyle,
            selectedKeys = loaded.selectedKeys,
            snackbarHostState = snackbarHostState,
            contentPadding = paddingValues,
            // Follows is a signed-in API listing, with no page to open and no source guide to read.
            onWebViewClick = null,
            onHelpClick = null,
            gestures = gestures,
        )
    }

    EntryAddDialogs(viewModel.addFlow)
    PendingBulkCategoryDialog(bulkFavoriteViewModel)
}
