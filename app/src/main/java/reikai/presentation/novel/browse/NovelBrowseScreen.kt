package reikai.presentation.novel.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.components.BrowseSourceLoadingItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import reikai.novel.host.NovelItem
import reikai.presentation.browse.EntryBrowseGridCell
import reikai.presentation.browse.components.BulkSelectionToolbar
import reikai.presentation.browse.toEntryBrowseUi
import reikai.presentation.novel.details.NovelCategoryDialog
import reikai.presentation.novel.details.NovelDetailsDialog
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus

/**
 * Per-source light-novel browse, rebuilt on Mihon's manga-browse primitives so it is visually
 * cohesive with the catalogue: an in-toolbar search, a Popular / Latest / Filter chip row, the same
 * empty / loading states, and the same comfortable grid cell. The source is pre-picked (constructor
 * arg, serializable); state lives in [NovelBrowseScreenModel]. Tapping a result is stubbed until the
 * novel details screen lands.
 */
class NovelBrowseScreen(
    private val sourceId: String,
    private val initialQuery: String = "",
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelBrowseScreenModel(sourceId, initialQuery) }
        val state by screenModel.state.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }
        // RK: shared bulk-selection add-to-library (Phase 5)
        val bulkModel = rememberScreenModel { NovelBulkFavoriteScreenModel() }
        val bulkState by bulkModel.state.collectAsState()

        BackHandler(enabled = bulkState.selectionMode) { bulkModel.backHandler() }

        var searchQuery by rememberSaveable { mutableStateOf<String?>(initialQuery.ifBlank { null }) }
        var selectingDisplayMode by remember { mutableStateOf(false) }
        // After "Open in WebView" (to clear Cloudflare), auto-retry the failed listing on return so the
        // user doesn't have to. Survives the activity stop the WebView causes.
        var pendingWebViewRetry by rememberSaveable { mutableStateOf(false) }

        val onWebViewClick: () -> Unit = {
            state.source?.site?.takeIf { it.isNotBlank() }?.let { url ->
                pendingWebViewRetry = true
                navigator.push(WebViewScreen(url = url, initialTitle = state.source?.name, sourceId = null))
            }
        }

        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            if (pendingWebViewRetry) {
                pendingWebViewRetry = false
                screenModel.retry()
            }
        }

        // Surface a fetch error as a retry snackbar only when results are already shown; an empty
        // listing routes the error through the EmptyScreen body instead. Tapping Retry re-runs the
        // failed page load (pagination is no longer latched off after an error).
        val errorString = state.error
        val retryLabel = stringResource(MR.strings.action_retry)
        LaunchedEffect(errorString) {
            if (errorString != null && state.novels.isNotEmpty()) {
                val result = snackbarHostState.showSnackbar(
                    message = errorString,
                    actionLabel = retryLabel,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) screenModel.loadMore()
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    // RK: while bulk-selecting, the selection bar replaces the search toolbar; the
                    //     Popular / Latest / Filter chip row below stays put (matches manga browse).
                    if (bulkState.selectionMode) {
                        BulkSelectionToolbar(
                            selectedCount = bulkState.selection.size,
                            onClickClearSelection = bulkModel::toggleSelectionMode,
                            onChangeCategoryClick = { bulkModel.addFavorite(state.favoritedKeys) },
                            onSelectAll = { state.novels.forEach { bulkModel.select(sourceId, it) } },
                            onReverseSelection = {
                                bulkModel.reverseSelection(state.novels.map { SelectedNovel(sourceId, it) })
                            },
                        )
                    } else {
                        SearchToolbar(
                            searchQuery = searchQuery,
                            onChangeSearchQuery = { searchQuery = it },
                            titleContent = { AppBarTitle(state.source?.name) },
                            navigateUp = navigator::pop,
                            placeholderText = stringResource(MR.strings.action_search),
                            onSearch = { screenModel.search(it) },
                            onClickCloseSearch = {
                                searchQuery = null
                                screenModel.search("")
                            },
                            actions = {
                                AppBarActions(
                                    actions = buildList {
                                        add(
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_display_mode),
                                                icon = if (screenModel.displayMode == LibraryDisplayMode.List) {
                                                    Icons.AutoMirrored.Filled.ViewList
                                                } else {
                                                    Icons.Filled.ViewModule
                                                },
                                                onClick = { selectingDisplayMode = true },
                                            ),
                                        )
                                        // RK: bulk-select entry (Phase 5)
                                        add(
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_bulk_select),
                                                icon = Icons.Outlined.Checklist,
                                                onClick = bulkModel::toggleSelectionMode,
                                            ),
                                        )
                                        add(
                                            AppBar.OverflowAction(
                                                title = stringResource(MR.strings.action_open_in_web_view),
                                                onClick = onWebViewClick,
                                            ),
                                        )
                                        if (state.source?.pluginSettings != null) {
                                            add(
                                                AppBar.OverflowAction(
                                                    title = stringResource(MR.strings.action_settings),
                                                    onClick = screenModel::openSettingsSheet,
                                                ),
                                            )
                                        }
                                    },
                                )

                                DropdownMenu(
                                    expanded = selectingDisplayMode,
                                    onDismissRequest = { selectingDisplayMode = false },
                                ) {
                                    RadioMenuItem(
                                        text = { Text(stringResource(MR.strings.action_display_comfortable_grid)) },
                                        isChecked = screenModel.displayMode == LibraryDisplayMode.ComfortableGrid,
                                    ) {
                                        selectingDisplayMode = false
                                        screenModel.displayMode = LibraryDisplayMode.ComfortableGrid
                                    }
                                    RadioMenuItem(
                                        text = { Text(stringResource(MR.strings.action_display_grid)) },
                                        isChecked = screenModel.displayMode == LibraryDisplayMode.CompactGrid,
                                    ) {
                                        selectingDisplayMode = false
                                        screenModel.displayMode = LibraryDisplayMode.CompactGrid
                                    }
                                    RadioMenuItem(
                                        text = { Text(stringResource(MR.strings.action_display_list)) },
                                        isChecked = screenModel.displayMode == LibraryDisplayMode.List,
                                    ) {
                                        selectingDisplayMode = false
                                        screenModel.displayMode = LibraryDisplayMode.List
                                    }
                                }
                            },
                            scrollBehavior = scrollBehavior,
                        )
                    }

                    val searching = state.query.isNotBlank()
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        ListingChip(
                            selected = !searching && state.listing == NovelBrowseState.Listing.Popular,
                            icon = Icons.Outlined.Favorite,
                            label = stringResource(MR.strings.popular),
                            onClick = {
                                searchQuery = null
                                screenModel.resetFilters()
                                screenModel.setListing(NovelBrowseState.Listing.Popular)
                            },
                        )
                        ListingChip(
                            selected = !searching && state.listing == NovelBrowseState.Listing.Latest,
                            icon = Icons.Outlined.NewReleases,
                            label = stringResource(MR.strings.latest),
                            onClick = {
                                searchQuery = null
                                screenModel.resetFilters()
                                screenModel.setListing(NovelBrowseState.Listing.Latest)
                            },
                        )
                        if (state.source?.filters?.isNotEmpty() == true) {
                            ListingChip(
                                selected = searching || state.hasActiveFilters,
                                icon = Icons.Outlined.FilterList,
                                label = stringResource(MR.strings.action_filter),
                                onClick = screenModel::openFilterSheet,
                            )
                        }
                    }

                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { contentPadding ->
            NovelBrowseBody(
                state = state,
                sourceId = sourceId,
                displayMode = screenModel.displayMode,
                contentPadding = contentPadding,
                selection = bulkState.selection,
                onRetry = screenModel::retry,
                onWebViewClick = onWebViewClick,
                // RK: tap toggles selection while bulk-selecting, long-press opens details (mirrors manga)
                onResultClick = { item ->
                    if (bulkState.selectionMode) {
                        bulkModel.toggleSelection(sourceId, item)
                    } else {
                        navigator.push(NovelScreen(sourceId, item.path))
                    }
                },
                onLongClickItem = { item ->
                    if (bulkState.selectionMode) {
                        navigator.push(NovelScreen(sourceId, item.path))
                    } else {
                        screenModel.onLongClickItem(item)
                    }
                },
                onLoadMore = screenModel::loadMore,
            )
        }

        when (val dialog = state.dialog) {
            is NovelBrowseDialog.AddDuplicate -> DuplicateNovelDialog(
                duplicates = dialog.duplicates,
                sourceNames = dialog.sourceNames,
                sourceSites = dialog.sourceSites,
                onDismissRequest = screenModel::dismissDialog,
                onConfirm = { screenModel.addFromDuplicate(dialog.item) },
                onOpenNovel = { navigator.push(NovelScreen(it.source, it.url)) },
            )
            is NovelBrowseDialog.ChangeCategory -> NovelCategoryDialog(
                dialog = NovelDetailsDialog.ChangeCategory(dialog.allCategories, dialog.currentCategoryIds),
                onDismiss = screenModel::dismissDialog,
                onConfirm = { screenModel.applyCategories(dialog.novelId, it) },
            )
            is NovelBrowseDialog.RemoveNovel -> RemoveNovelDialog(
                title = dialog.item.name,
                onDismiss = screenModel::dismissDialog,
                onConfirm = { screenModel.confirmRemove(dialog.item) },
            )
            null -> {}
        }

        // RK: bulk add-to-library category picker (Phase 5), one choice applied to the whole selection.
        when (val bulkDialog = bulkState.dialog) {
            is NovelBulkFavoriteScreenModel.Dialog.ChangeCategory -> NovelCategoryDialog(
                dialog = NovelDetailsDialog.ChangeCategory(bulkDialog.categories, emptySet()),
                onDismiss = { bulkModel.setDialog(null) },
                onConfirm = { bulkModel.setNovelsCategories(bulkDialog.items, it) },
            )
            null -> {}
        }

        val source = state.source
        if (state.filterSheetOpen && source != null) {
            NovelSourceFilterSheet(
                filters = source.filters,
                values = state.filterValues,
                onValueChange = screenModel::setFilterValue,
                onApply = screenModel::applyFilters,
                onReset = screenModel::resetFilters,
                onDismiss = screenModel::closeFilterSheet,
            )
        }
        if (state.settingsSheetOpen && source != null) {
            NovelSourceSettingsSheet(source = source, onDismiss = screenModel::closeSettingsSheet)
        }
    }
}

@Composable
private fun ListingChip(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        leadingIcon = {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
        },
        label = { Text(text = label) },
    )
}

@Composable
private fun NovelBrowseBody(
    state: NovelBrowseState,
    sourceId: String,
    displayMode: LibraryDisplayMode,
    contentPadding: PaddingValues,
    selection: List<SelectedNovel>,
    onRetry: () -> Unit,
    onWebViewClick: () -> Unit,
    onResultClick: (NovelItem) -> Unit,
    onLongClickItem: (NovelItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    when {
        state.source == null && state.error != null -> EmptyScreen(
            message = state.error,
            modifier = Modifier.padding(contentPadding),
            actions = listOf(
                EmptyScreenAction(MR.strings.action_retry, Icons.Outlined.Refresh, onRetry),
            ),
        )
        state.loading && state.novels.isEmpty() -> LoadingScreen(Modifier.padding(contentPadding))
        state.novels.isEmpty() -> EmptyScreen(
            message = state.error ?: stringResource(MR.strings.no_results_found),
            modifier = Modifier.padding(contentPadding),
            actions = listOf(
                EmptyScreenAction(MR.strings.action_retry, Icons.Outlined.Refresh, onRetry),
                EmptyScreenAction(MR.strings.action_open_in_web_view, Icons.Outlined.Public, onWebViewClick),
            ),
        )
        displayMode == LibraryDisplayMode.List -> {
            val haptic = LocalHapticFeedback.current
            val listState = rememberLazyListState()
            LazyColumn(
                state = listState,
                contentPadding = contentPadding + PaddingValues(vertical = 8.dp),
            ) {
                items(items = state.novels, key = { it.path }) { item ->
                    EntryBrowseGridCell(
                        ui = item.toEntryBrowseUi(
                            inLibrary = (sourceId to item.path) in state.favoritedKeys,
                            site = state.source?.site,
                        ),
                        displayMode = displayMode,
                        onClick = { onResultClick(item) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClickItem(item)
                        },
                        isSelected = selection.fastAny { it.sourceId == sourceId && it.item.path == item.path },
                    )
                }
                if (state.loadingMore) {
                    item { BrowseSourceLoadingItem() }
                }
            }
            LoadMoreOnScrollEnd(
                totalItems = { listState.layoutInfo.totalItemsCount },
                lastVisible = { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
                key = state.novels,
                onLoadMore = onLoadMore,
            )
        }
        else -> {
            val haptic = LocalHapticFeedback.current
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(128.dp),
                state = gridState,
                contentPadding = contentPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
                horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
            ) {
                items(items = state.novels, key = { it.path }) { item ->
                    EntryBrowseGridCell(
                        ui = item.toEntryBrowseUi(
                            inLibrary = (sourceId to item.path) in state.favoritedKeys,
                            site = state.source?.site,
                        ),
                        displayMode = displayMode,
                        onClick = { onResultClick(item) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClickItem(item)
                        },
                        isSelected = selection.fastAny { it.sourceId == sourceId && it.item.path == item.path },
                    )
                }
                if (state.loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }) { BrowseSourceLoadingItem() }
                }
            }
            LoadMoreOnScrollEnd(
                totalItems = { gridState.layoutInfo.totalItemsCount },
                lastVisible = { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 },
                key = state.novels,
                onLoadMore = onLoadMore,
            )
        }
    }
}

/** Confirm removing a favorited result from the library, the novel twin of `RemoveMangaDialog`.
 *  Shared with the novel global search, which reuses the same long-press remove flow. */
@Composable
internal fun RemoveNovelDialog(title: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.are_you_sure)) },
        text = { Text(stringResource(MR.strings.remove_manga, title)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) { Text(stringResource(MR.strings.action_remove)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

/**
 * Fires [onLoadMore] once when the grid scrolls within [PREFETCH_DISTANCE] items of the end.
 * `distinctUntilChanged` plus the model's loading/end guards keep it from looping while a fetch is in
 * flight. [key] re-arms the watch when the backing list changes.
 */
@Composable
private fun LoadMoreOnScrollEnd(
    totalItems: () -> Int,
    lastVisible: () -> Int,
    key: Any,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(key) {
        snapshotFlow {
            val total = totalItems()
            total > 0 && lastVisible() >= total - 1 - PREFETCH_DISTANCE
        }
            .distinctUntilChanged()
            .collect { nearEnd -> if (nearEnd) onLoadMore() }
    }
}

private const val PREFETCH_DISTANCE = 6
