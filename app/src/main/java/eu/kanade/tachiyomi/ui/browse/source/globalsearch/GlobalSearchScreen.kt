package eu.kanade.tachiyomi.ui.browse.source.globalsearch

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.GlobalSearchScreen
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import reikai.domain.library.ContentType
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.components.BulkFavoriteDialogs
import reikai.presentation.browse.components.EntryDuplicateDialog
import reikai.presentation.browse.components.EntryRemoveDialog
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.migrate.flow.EntryMigrateFor
import tachiyomi.presentation.core.screens.LoadingScreen

class GlobalSearchScreen(
    val searchQuery: String = "",
    private val extensionFilter: String? = null,
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val navigator = LocalNavigator.currentOrThrow
        val haptic = LocalHapticFeedback.current

        val viewModel = assistedMetroViewModel<GlobalSearchViewModel, GlobalSearchViewModel.Factory> {
            create(initialQuery = searchQuery, initialExtensionFilter = extensionFilter)
        }
        val state by viewModel.state.collectAsState()

        // RK: shared bulk-selection
        val bulkFavoriteViewModel = metroViewModel<BulkFavoriteViewModel>()
        val bulkFavoriteState by bulkFavoriteViewModel.state.collectAsState()
        BackHandler(enabled = bulkFavoriteState.selectionMode) {
            bulkFavoriteViewModel.backHandler()
        }

        var showSingleLoadingScreen by remember {
            mutableStateOf(searchQuery.isNotEmpty() && !extensionFilter.isNullOrEmpty() && state.total == 1)
        }

        if (showSingleLoadingScreen) {
            LoadingScreen()

            LaunchedEffect(state.items) {
                when (val result = state.items.values.singleOrNull()) {
                    SearchItemResult.Loading -> return@LaunchedEffect
                    is SearchItemResult.Success -> {
                        val manga = result.result.singleOrNull()
                        if (manga != null) {
                            navigator.replace(MangaScreen(manga.id, true))
                        } else {
                            // Backoff to result screen
                            showSingleLoadingScreen = false
                        }
                    }
                    else -> showSingleLoadingScreen = false
                }
            }
        } else {
            GlobalSearchScreen(
                state = state,
                navigateUp = navigator::pop,
                onChangeSearchQuery = viewModel::updateSearchQuery,
                onSearch = { viewModel.search() },
                getManga = { viewModel.getManga(it) },
                onChangeSearchFilter = viewModel::setSourceFilter,
                onToggleResults = viewModel::toggleFilterResults,
                onClickSource = {
                    navigator.push(BrowseSourceScreen(it.id, state.searchQuery))
                },
                onClickItem = { manga ->
                    // RK: tap toggles selection while bulk-selecting
                    if (bulkFavoriteState.selectionMode) {
                        bulkFavoriteViewModel.toggleSelection(manga)
                    } else {
                        navigator.push(MangaScreen(manga.id, true))
                    }
                },
                // RK: long-press opens while bulk-selecting, otherwise adds to / removes from library.
                onLongClickItem = { manga ->
                    if (bulkFavoriteState.selectionMode) {
                        navigator.push(MangaScreen(manga.id, true))
                    } else {
                        viewModel.onLongClick(manga)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                // RK: bulk-selection
                selectionMode = bulkFavoriteState.selectionMode,
                selection = bulkFavoriteState.selection,
                onToggleSelectionMode = bulkFavoriteViewModel::toggleSelectionMode,
                onClickAddToLibrary = bulkFavoriteViewModel::addFavorite,
            )
        }

        // RK --> long-press add-to-library dialogs, mirroring BrowseSourceScreen
        val onDismissRequest = viewModel::clearDialog
        when (val dialog = state.dialog) {
            is SearchViewModel.Dialog.AddDuplicateManga -> {
                EntryDuplicateDialog(
                    duplicates = dialog.duplicates,
                    toUi = { it.toDuplicateCard(dialog.sourceLabels) },
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.addFavorite(dialog.manga) },
                    onOpen = { navigator.push(MangaScreen(it.manga.id)) },
                    onMigrate = { viewModel.setMigrateDialog(it.manga.id, dialog.manga) },
                    // RK: offer grouping when the same-title suggestion pref is on.
                    groupIdByEntryId = dialog.groupIdByMangaId,
                    onAddToGroup = { selectedIds: List<Long> ->
                        viewModel.addToExistingGroup(dialog.manga, selectedIds)
                    }.takeIf { dialog.suggestGroup },
                )
            }
            is SearchViewModel.Dialog.Migrate -> {
                EntryMigrateFor(
                    contentType = ContentType.MANGA,
                    currentId = dialog.current.id,
                    targetId = dialog.target.id,
                    onDismissRequest = onDismissRequest,
                )
            }
            is SearchViewModel.Dialog.RemoveManga -> {
                EntryRemoveDialog(
                    title = dialog.manga.title,
                    onDismissRequest = onDismissRequest,
                    onConfirm = { viewModel.changeMangaFavorite(dialog.manga) },
                )
            }
            is SearchViewModel.Dialog.ChangeMangaCategory -> {
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
        // RK <--

        // RK: bulk-selection dialogs
        BulkFavoriteDialogs(
            bulkFavoriteViewModel = bulkFavoriteViewModel,
            dialog = bulkFavoriteState.dialog,
        )
    }
}
