package exh.md.follows

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.presentation.core.util.collectAsLazyPagingItems
import reikai.domain.library.ContentType
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.catalogue.EntryBrowseCatalogue
import reikai.presentation.browse.catalogue.EntryBrowseScreenState
import reikai.presentation.browse.catalogue.MangaBrowseAdapter
import reikai.presentation.browse.catalogue.manga
import reikai.presentation.browse.components.BulkFavoriteDialogs
import reikai.presentation.browse.components.BulkSelectionToolbar
import reikai.presentation.browse.components.EntryDuplicateDialog
import reikai.presentation.browse.components.EntryRemoveDialog
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.migrate.flow.EntryMigrateFor
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
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow
        val haptic = LocalHapticFeedback.current
        val viewModel = assistedMetroViewModel<MangaDexFollowsViewModel, MangaDexFollowsViewModel.Factory> {
            create(sourceId = sourceId)
        }
        val state by viewModel.state.collectAsState()
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
                                            icon = Icons.Outlined.Checklist,
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
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = {},
                onHelpClick = {},
                onClick = { row ->
                    if (bulkFavoriteState.selectionMode) {
                        adapter.toggleSelection(row)
                    } else {
                        navigator.push(MangaScreen(row.manga.id, true))
                    }
                },
                onLongClick = { row ->
                    if (bulkFavoriteState.selectionMode) {
                        navigator.push(MangaScreen(row.manga.id, true))
                    } else {
                        adapter.onRowLongClick(row)
                    }
                },
            )
        }

        val onDismissRequest = { viewModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseSourceViewModel.Dialog.AddDuplicateManga -> {
                EntryDuplicateDialog(
                    duplicates = dialog.duplicates,
                    toUi = { it.toDuplicateCard(dialog.sourceLabels) },
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.addFavorite(dialog.manga) },
                    onOpen = { navigator.push(MangaScreen(it.manga.id)) },
                    onMigrate = {
                        viewModel.setDialog(BrowseSourceViewModel.Dialog.Migrate(dialog.manga, it.manga))
                    },
                    // RK: offer grouping when the same-title suggestion pref is on.
                    groupIdByEntryId = dialog.groupIdByMangaId,
                    onAddToGroup = { selectedIds: List<Long> ->
                        viewModel.addToExistingGroup(dialog.manga, selectedIds)
                    }.takeIf { dialog.suggestGroup },
                )
            }
            is BrowseSourceViewModel.Dialog.Migrate -> {
                EntryMigrateFor(
                    contentType = ContentType.MANGA,
                    currentId = dialog.current.id,
                    targetId = dialog.target.id,
                    onDismissRequest = onDismissRequest,
                )
            }
            is BrowseSourceViewModel.Dialog.RemoveManga -> {
                EntryRemoveDialog(
                    title = dialog.manga.title,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.changeMangaFavorite(dialog.manga) },
                )
            }
            is BrowseSourceViewModel.Dialog.ChangeMangaCategory -> {
                ChangeCategoryDialog(
                    initialSelection = dialog.initialSelection,
                    onDismissRequest = onDismissRequest,
                    onEditCategories = { navigator.push(CategoryScreen()) },
                    onConfirm = { include, _ ->
                        viewModel.confirmCategories(dialog.manga, include, dialog.alreadyFavorited)
                    },
                )
            }
            else -> {}
        }

        BulkFavoriteDialogs(
            bulkFavoriteViewModel = bulkFavoriteViewModel,
            dialog = bulkFavoriteState.dialog,
        )
    }
}
