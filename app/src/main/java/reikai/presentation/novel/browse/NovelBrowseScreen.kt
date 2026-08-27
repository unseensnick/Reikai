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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.components.BrowseSourceLoadingItem
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.components.RadioMenuItem
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.network.interceptor.cloudflareBlockedUrl
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import mihon.presentation.core.util.collectAsLazyPagingItems
import reikai.domain.library.ContentType
import reikai.novel.host.NovelItem
import reikai.presentation.browse.EntryBrowseGridCell
import reikai.presentation.browse.EntryBulkFavoriteViewModel
import reikai.presentation.browse.components.BulkSelectionToolbar
import reikai.presentation.browse.components.EntryDuplicateDialog
import reikai.presentation.browse.components.EntryRemoveDialog
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.browse.toEntryBrowseUi
import reikai.presentation.migrate.flow.EntryMigrateFor
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
 * empty / loading states, the same Paging 3 pager and the same comfortable grid cell. The source is
 * pre-picked (constructor arg, serializable); state lives in [NovelBrowseViewModel]. Tapping a result
 * opens the novel details screen.
 */
class NovelBrowseScreen(
    private val sourceId: String,
    private val initialQuery: String = "",
    /** Set when browsing to choose a migration target for this novel: a tap reports the pick back
     *  instead of opening details. */
    private val migratePickFor: Long? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = assistedMetroViewModel<NovelBrowseViewModel, NovelBrowseViewModel.Factory> {
            create(sourceId = sourceId, initialQuery = initialQuery)
        }
        val state by viewModel.state.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }
        val bulkModel = metroViewModel<NovelBulkFavoriteViewModel>()
        val bulkState by bulkModel.state.collectAsState()
        val novels = viewModel.novelPagerFlowFlow.collectAsLazyPagingItems()

        BackHandler(enabled = bulkState.selectionMode) { bulkModel.backHandler() }

        var searchQuery by rememberSaveable { mutableStateOf<String?>(initialQuery.ifBlank { null }) }
        var selectingDisplayMode by remember { mutableStateOf(false) }
        // After "Open in WebView" (to clear Cloudflare), auto-retry the failed listing on return so the
        // user doesn't have to. Survives the activity stop the WebView causes.
        var pendingWebViewRetry by rememberSaveable { mutableStateOf(false) }

        val errorState = novels.loadState.refresh.takeIf { it is LoadState.Error }
            ?: novels.loadState.append.takeIf { it is LoadState.Error }

        // Prefer the URL the challenge blocked: a plugin site root is often not challenged at all, so
        // opening it showed a working page with nothing to solve and left every retry failing.
        val challengeUrl = (errorState as? LoadState.Error)?.error?.cloudflareBlockedUrl()
        val onWebViewClick: () -> Unit = {
            (challengeUrl ?: state.source?.site)?.takeIf { it.isNotBlank() }?.let { url ->
                pendingWebViewRetry = true
                navigator.push(WebViewScreen(url = url, initialTitle = state.source?.name, sourceId = null))
            }
        }

        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            if (pendingWebViewRetry) {
                pendingWebViewRetry = false
                novels.refresh()
            }
        }

        // Surface a fetch error as a retry snackbar only when results are already shown; an empty
        // listing routes the error through the EmptyScreen body instead.
        val retryLabel = stringResource(MR.strings.action_retry)
        LaunchedEffect(errorState) {
            if (novels.itemCount > 0 && errorState is LoadState.Error) {
                val result = snackbarHostState.showSnackbar(
                    message = with(context) { errorState.error.formattedMessage },
                    actionLabel = retryLabel,
                    // Indefinite (matches manga's browse retry snackbar): an error should stay visible
                    // until the user dismisses or retries it, not auto-dismiss after a few seconds.
                    duration = SnackbarDuration.Indefinite,
                )
                if (result == SnackbarResult.ActionPerformed) novels.retry()
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
                    // While bulk-selecting, the selection bar replaces the search toolbar; the
                    // Popular / Latest / Filter chip row below stays put (matches manga browse).
                    if (bulkState.selectionMode) {
                        BulkSelectionToolbar(
                            selectedCount = bulkState.selection.size,
                            onClickClearSelection = bulkModel::toggleSelectionMode,
                            onChangeCategoryClick = { bulkModel.addFavorite(state.favoritedKeys) },
                            onSelectAll = {
                                novels.itemSnapshotList.items.forEach { bulkModel.select(sourceId, it) }
                            },
                            onReverseSelection = {
                                bulkModel.reverseSelection(
                                    novels.itemSnapshotList.items.map { SelectedNovel(sourceId, it) },
                                )
                            },
                        )
                    } else {
                        SearchToolbar(
                            searchQuery = searchQuery,
                            onChangeSearchQuery = { searchQuery = it },
                            titleContent = { AppBarTitle(state.source?.name) },
                            navigateUp = navigator::pop,
                            placeholderText = stringResource(MR.strings.action_search),
                            onSearch = { viewModel.search(it) },
                            onClickCloseSearch = {
                                searchQuery = null
                                viewModel.search("")
                            },
                            actions = {
                                AppBarActions(
                                    actions = buildList {
                                        add(
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_display_mode),
                                                icon = if (viewModel.displayMode == LibraryDisplayMode.List) {
                                                    Icons.AutoMirrored.Filled.ViewList
                                                } else {
                                                    Icons.Filled.ViewModule
                                                },
                                                onClick = { selectingDisplayMode = true },
                                            ),
                                        )
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
                                                    onClick = viewModel::openSettingsSheet,
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
                                        isChecked = viewModel.displayMode == LibraryDisplayMode.ComfortableGrid,
                                    ) {
                                        selectingDisplayMode = false
                                        viewModel.displayMode = LibraryDisplayMode.ComfortableGrid
                                    }
                                    RadioMenuItem(
                                        text = { Text(stringResource(MR.strings.action_display_grid)) },
                                        isChecked = viewModel.displayMode == LibraryDisplayMode.CompactGrid,
                                    ) {
                                        selectingDisplayMode = false
                                        viewModel.displayMode = LibraryDisplayMode.CompactGrid
                                    }
                                    RadioMenuItem(
                                        text = { Text(stringResource(MR.strings.action_display_list)) },
                                        isChecked = viewModel.displayMode == LibraryDisplayMode.List,
                                    ) {
                                        selectingDisplayMode = false
                                        viewModel.displayMode = LibraryDisplayMode.List
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
                                viewModel.resetFilters()
                                viewModel.setListing(NovelBrowseState.Listing.Popular)
                            },
                        )
                        ListingChip(
                            selected = !searching && state.listing == NovelBrowseState.Listing.Latest,
                            icon = Icons.Outlined.NewReleases,
                            label = stringResource(MR.strings.latest),
                            onClick = {
                                searchQuery = null
                                viewModel.resetFilters()
                                viewModel.setListing(NovelBrowseState.Listing.Latest)
                            },
                        )
                        if (state.source?.filters?.isNotEmpty() == true) {
                            ListingChip(
                                selected = searching || state.hasActiveFilters,
                                icon = Icons.Outlined.FilterList,
                                label = stringResource(MR.strings.action_filter),
                                onClick = viewModel::openFilterSheet,
                            )
                        }
                    }

                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { contentPadding ->
            NovelBrowseBody(
                novels = novels,
                state = state,
                errorState = errorState as? LoadState.Error,
                sourceId = sourceId,
                displayMode = viewModel.displayMode,
                contentPadding = contentPadding,
                selection = bulkState.selection,
                onRetrySource = viewModel::retryLoadSource,
                onWebViewClick = onWebViewClick,
                onResultClick = { item ->
                    when {
                        // Picking a migration target; store the row and hand its id back.
                        migratePickFor != null -> viewModel.pickAsMigrationTarget(item, migratePickFor) {
                            navigator.pop()
                        }
                        bulkState.selectionMode -> bulkModel.toggleSelection(sourceId, item)
                        else -> navigator.push(NovelScreen(sourceId, item.path))
                    }
                },
                onLongClickItem = { item ->
                    // In pick mode long-press previews the candidate, matching the manga picker.
                    // The normal long-press adds to library, which would favorite a novel the user
                    // is only inspecting before choosing a migration target.
                    if (migratePickFor != null || bulkState.selectionMode) {
                        navigator.push(NovelScreen(sourceId, item.path))
                    } else {
                        viewModel.onLongClickItem(item)
                    }
                },
            )
        }

        when (val dialog = state.dialog) {
            is NovelBrowseDialog.AddDuplicate -> EntryDuplicateDialog(
                duplicates = dialog.duplicates,
                toUi = { it.toDuplicateCard(dialog.sourceLabels, dialog.sourceSites) },
                onDismissRequest = viewModel::dismissDialog,
                onConfirm = { viewModel.addFromDuplicate(dialog.item) },
                onOpen = { navigator.push(NovelScreen(it.novel.source, it.novel.url)) },
                onMigrate = { dup -> viewModel.startMigrate(dup.novel.id, dialog.item) },
                groupIdByEntryId = dialog.groupIdByNovelId,
                onAddToGroup = { selectedIds: List<Long> ->
                    viewModel.addToExistingGroup(dialog.item, selectedIds)
                }.takeIf { dialog.suggestGroup },
            )
            is NovelBrowseDialog.ChangeCategory -> ChangeCategoryDialog(
                initialSelection = dialog.initialSelection,
                onDismissRequest = viewModel::dismissDialog,
                onEditCategories = { navigator.push(CategoryScreen()) },
                onConfirm = { include, _ -> viewModel.applyCategories(dialog.target, include) },
            )
            is NovelBrowseDialog.RemoveNovel -> EntryRemoveDialog(
                title = dialog.item.name,
                onDismissRequest = viewModel::dismissDialog,
                onConfirm = { viewModel.confirmRemove(dialog.item) },
            )
            is NovelBrowseDialog.Migrate -> EntryMigrateFor(
                contentType = ContentType.NOVELS,
                currentId = dialog.currentId,
                targetId = dialog.targetId,
                onDismissRequest = viewModel::dismissDialog,
            )
            null -> {}
        }

        // Bulk add-to-library category picker, one choice applied to the whole selection.
        when (val bulkDialog = bulkState.dialog) {
            is EntryBulkFavoriteViewModel.Dialog.ChangeCategory -> ChangeCategoryDialog(
                initialSelection = bulkDialog.initialSelection,
                onDismissRequest = { bulkModel.setDialog(null) },
                onEditCategories = { navigator.push(CategoryScreen()) },
                onConfirm = { include, _ -> bulkModel.setCategories(bulkDialog.items, include) },
            )
            null -> {}
        }

        val source = state.source
        if (state.filterSheetOpen && source != null) {
            NovelSourceFilterSheet(
                filters = source.filters,
                values = state.filterValues,
                onValueChange = viewModel::setFilterValue,
                onApply = viewModel::applyFilters,
                onReset = viewModel::resetFilters,
                onDismiss = viewModel::closeFilterSheet,
            )
        }
        if (state.settingsSheetOpen && source != null) {
            NovelSourceSettingsSheet(source = source, onDismiss = viewModel::closeSettingsSheet)
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
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
            label = { Text(text = label) },
        )
    }

    @Composable
    private fun NovelBrowseBody(
        novels: LazyPagingItems<NovelItem>,
        state: NovelBrowseState,
        errorState: LoadState.Error?,
        sourceId: String,
        displayMode: LibraryDisplayMode,
        contentPadding: PaddingValues,
        selection: List<SelectedNovel>,
        onRetrySource: () -> Unit,
        onWebViewClick: () -> Unit,
        onResultClick: (NovelItem) -> Unit,
        onLongClickItem: (NovelItem) -> Unit,
    ) {
        val context = LocalContext.current
        when {
            // The plugin itself never resolved, so there is nothing to page and nothing for the
            // pager to report; only re-resolving it can recover.
            state.source == null && state.sourceError != null -> EmptyScreen(
                message = state.sourceError,
                modifier = Modifier.padding(contentPadding),
                actions = listOf(
                    EmptyScreenAction(MR.strings.action_retry, Icons.Outlined.Refresh, onRetrySource),
                ),
            )
            novels.itemCount == 0 && novels.loadState.refresh is LoadState.Loading ->
                LoadingScreen(Modifier.padding(contentPadding))
            novels.itemCount == 0 -> EmptyScreen(
                message = errorState?.let { with(context) { it.error.formattedMessage } }
                    ?: stringResource(MR.strings.no_results_found),
                modifier = Modifier.padding(contentPadding),
                actions = listOf(
                    EmptyScreenAction(MR.strings.action_retry, Icons.Outlined.Refresh, novels::refresh),
                    EmptyScreenAction(MR.strings.action_open_in_web_view, Icons.Outlined.Public, onWebViewClick),
                ),
            )
            displayMode == LibraryDisplayMode.List -> {
                val haptic = LocalHapticFeedback.current
                LazyColumn(contentPadding = contentPadding + PaddingValues(vertical = 8.dp)) {
                    items(count = novels.itemCount) { index ->
                        val item = novels[index] ?: return@items
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
                    if (novels.loadState.append is LoadState.Loading) {
                        item { BrowseSourceLoadingItem() }
                    }
                }
            }
            else -> {
                val haptic = LocalHapticFeedback.current
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(128.dp),
                    contentPadding = contentPadding + PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
                    horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
                ) {
                    items(count = novels.itemCount) { index ->
                        val item = novels[index] ?: return@items
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
                    if (novels.loadState.append is LoadState.Loading) {
                        item(span = { GridItemSpan(maxLineSpan) }) { BrowseSourceLoadingItem() }
                    }
                }
            }
        }
    }
}
