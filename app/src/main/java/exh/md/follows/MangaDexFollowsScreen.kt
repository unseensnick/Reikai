package exh.md.follows

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.SelectAll
import mihon.presentation.core.util.collectAsLazyPagingItems
import reikai.domain.source.SourceKey
import reikai.presentation.browse.BulkCategoryDialog
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.EntryAddDialogs
import reikai.presentation.browse.catalogue.EntryBrowseCatalogue
import reikai.presentation.browse.catalogue.EntryBrowseScreenState
import reikai.presentation.browse.catalogue.MangaBrowseAdapter
import reikai.presentation.browse.components.BulkSelectionToolbar
import reikai.presentation.browse.detailsScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Browse the signed-in user's MangaDex follows. Reuses the source-browse grid and favoriting flow
 * via [MangaDexFollowsViewModel], plus the shared bulk-selection toolbar for adding many at once.
 * Reached from the browse filter sheet's Follows button.
 */
class MangaDexFollowsScreen(private val sourceId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<MangaDexFollowsViewModel, MangaDexFollowsViewModel.Factory> {
            create(sourceId = sourceId)
        }
        val snackbarHostState = remember { SnackbarHostState() }

        val bulkFavoriteViewModel = metroViewModel<BulkFavoriteViewModel>()
        val bulkFavoriteState by bulkFavoriteViewModel.state.collectAsState()
        val adapter = remember(viewModel, bulkFavoriteViewModel) {
            MangaBrowseAdapter(viewModel, bulkFavoriteViewModel)
        }
        val entries = adapter.rows.collectAsLazyPagingItems()
        val browseState by adapter.state.collectAsState()

        BackHandler(enabled = bulkFavoriteState.selectionMode) {
            bulkFavoriteViewModel.backHandler()
        }

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
            val loaded = browseState as? EntryBrowseScreenState.Loaded
            if (loaded == null) {
                LoadingScreen(Modifier.padding(paddingValues))
                return@Scaffold
            }
            EntryBrowseCatalogue(
                rows = entries,
                rowStyle = loaded.rowStyle,
                selectedKeys = loaded.selectedKeys,
                longPressOpensEntry = bulkFavoriteState.selectionMode,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = {},
                onHelpClick = {},
                onClick = { row ->
                    if (bulkFavoriteState.selectionMode) {
                        adapter.toggleSelection(row)
                    } else {
                        navigator.push(row.detailsScreen(SourceKey.Manga(sourceId)))
                    }
                },
                onLongClick = { row ->
                    if (bulkFavoriteState.selectionMode) {
                        navigator.push(row.detailsScreen(SourceKey.Manga(sourceId)))
                    } else {
                        adapter.onRowLongClick(row)
                    }
                },
            )
        }

        EntryAddDialogs(viewModel.addFlow)
        bulkFavoriteState.dialog?.let { BulkCategoryDialog(bulkFavoriteViewModel, it) }
    }
}
