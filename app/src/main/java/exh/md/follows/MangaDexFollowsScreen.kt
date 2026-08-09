package exh.md.follows

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.DuplicateMangaDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.presentation.core.util.collectAsLazyPagingItems
import reikai.domain.library.ContentType
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.components.BulkFavoriteDialogs
import reikai.presentation.browse.components.BulkSelectionToolbar
import reikai.presentation.browse.components.EntryRemoveDialog
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
        val viewModel = viewModel<MangaDexFollowsViewModel>(
            factory = MangaDexFollowsViewModel.Factory,
            extras = CreationExtras { set(MangaDexFollowsViewModel.SOURCE_ID_KEY, sourceId) },
        )
        val state by viewModel.state.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        val bulkFavoriteViewModel = viewModel<BulkFavoriteViewModel>()
        val bulkFavoriteState by bulkFavoriteViewModel.state.collectAsState()
        val mangaList = viewModel.mangaPagerFlowFlow.collectAsLazyPagingItems()

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
                        onSelectAll = {
                            mangaList.itemSnapshotList.items
                                .map { it.value.first }
                                .forEach(bulkFavoriteViewModel::select)
                        },
                        onReverseSelection = {
                            bulkFavoriteViewModel.reverseSelection(
                                mangaList.itemSnapshotList.items.map { it.value.first },
                            )
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
            BrowseSourceContent(
                source = viewModel.source,
                mangaList = mangaList,
                columns = viewModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = viewModel.displayMode,
                useEhentaiView = viewModel.useEhentaiView,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = {},
                onHelpClick = {},
                onLocalSourceHelpClick = {},
                onMangaClick = { manga ->
                    if (bulkFavoriteState.selectionMode) {
                        bulkFavoriteViewModel.toggleSelection(manga)
                    } else {
                        navigator.push(MangaScreen(manga.id, true))
                    }
                },
                onMangaLongClick = { manga ->
                    if (bulkFavoriteState.selectionMode) {
                        navigator.push(MangaScreen(manga.id, true))
                    } else {
                        viewModel.onLongClick(manga)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                selection = bulkFavoriteState.selection,
            )
        }

        val onDismissRequest = { viewModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is BrowseSourceViewModel.Dialog.AddDuplicateManga -> {
                DuplicateMangaDialog(
                    duplicates = dialog.duplicates,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.addFavorite(dialog.manga) },
                    onOpenManga = { navigator.push(MangaScreen(it.id)) },
                    onMigrate = {
                        viewModel.setDialog(BrowseSourceViewModel.Dialog.Migrate(dialog.manga, it))
                    },
                    // RK: offer grouping when the same-title suggestion pref is on.
                    groupIdByMangaId = dialog.groupIdByMangaId,
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
