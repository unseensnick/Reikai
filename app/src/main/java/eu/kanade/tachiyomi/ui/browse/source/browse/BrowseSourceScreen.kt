package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.MissingSourceScreen
import eu.kanade.presentation.browse.components.BrowseSourceToolbar
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.all.MangaDex
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel.Listing
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import exh.md.follows.MangaDexFollowsScreen
import exh.source.getMainSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
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
import tachiyomi.core.common.Constants
import tachiyomi.domain.source.model.StubSource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

data class BrowseSourceScreen(
    val sourceId: Long,
    private val listingQuery: String?,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }

        val viewModel = assistedMetroViewModel<BrowseSourceViewModel, BrowseSourceViewModel.Factory> {
            create(sourceId = sourceId, listingQuery = listingQuery)
        }
        val state by viewModel.state.collectAsState()

        val navigator = LocalNavigator.currentOrThrow
        val navigateUp: () -> Unit = {
            when {
                !state.isUserQuery && state.toolbarQuery != null -> viewModel.setToolbarQuery(null)
                else -> navigator.pop()
            }
        }

        if (viewModel.source is StubSource) {
            MissingSourceScreen(
                source = viewModel.source,
                navigateUp = navigateUp,
            )
            return
        }

        // RK: navigate to the random MangaDex title once its id has been fetched (async).
        LaunchedEffect(state.randomMangaTarget) {
            val target = state.randomMangaTarget ?: return@LaunchedEffect
            viewModel.consumeRandomTarget()
            navigator.push(BrowseSourceScreen(sourceId, target))
        }

        val haptic = LocalHapticFeedback.current
        val uriHandler = LocalUriHandler.current
        val snackbarHostState = remember { SnackbarHostState() }

        // RK: shared bulk-selection
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

        val onHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
        // RK --> open the URL the Cloudflare challenge actually blocked when we have it. The source
        //     root is frequently not challenged, so opening it showed a working page with nothing to
        //     solve and left every retry failing. Coming back re-runs the listing, as novels do.
        var pendingWebViewRetry by rememberSaveable { mutableStateOf(false) }
        val onWebViewClick = f@{ challengeUrl: String? ->
            val source = viewModel.source as? HttpSource ?: return@f
            pendingWebViewRetry = true
            navigator.push(
                WebViewScreen(
                    url = challengeUrl ?: source.getHomeUrl(),
                    initialTitle = source.name,
                    sourceId = source.id,
                ),
            )
        }

        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            if (pendingWebViewRetry) {
                pendingWebViewRetry = false
                entries.refresh()
            }
        }
        // RK <--

        LaunchedEffect(viewModel.source) {
            assistUrl = (viewModel.source as? HttpSource)?.getHomeUrl()
        }

        Scaffold(
            topBar = {
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        .pointerInput(Unit) {},
                ) {
                    // RK: while bulk-selecting, the selection bar replaces the search toolbar; the
                    //     Popular / Latest / Filter chips below stay put (matches Komikku).
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
                        BrowseSourceToolbar(
                            searchQuery = state.toolbarQuery,
                            onSearchQueryChange = viewModel::setToolbarQuery,
                            source = viewModel.source,
                            displayMode = viewModel.displayMode,
                            onDisplayModeChange = { viewModel.displayMode = it },
                            navigateUp = navigateUp,
                            onWebViewClick = { onWebViewClick(null) },
                            onHelpClick = onHelpClick,
                            onSettingsClick = { navigator.push(SourcePreferencesScreen(sourceId)) },
                            onSearch = viewModel::search,
                            // RK: bulk-select entry
                            onToggleSelectionMode = bulkFavoriteViewModel::toggleSelectionMode,
                        )
                    }

                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = MaterialTheme.padding.small),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    ) {
                        FilterChip(
                            selected = state.listing == Listing.Popular,
                            onClick = {
                                viewModel.resetFilters()
                                viewModel.setListing(Listing.Popular)
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Outlined.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .size(FilterChipDefaults.IconSize),
                                )
                            },
                            label = {
                                Text(text = stringResource(MR.strings.popular))
                            },
                        )
                        if (viewModel.source.supportsLatest) {
                            FilterChip(
                                selected = state.listing == Listing.Latest,
                                onClick = {
                                    viewModel.resetFilters()
                                    viewModel.setListing(Listing.Latest)
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.NewReleases,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.latest))
                                },
                            )
                        }
                        if (state.filters.isNotEmpty()) {
                            FilterChip(
                                selected = state.listing is Listing.Search,
                                onClick = viewModel::openFilterSheet,
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Outlined.FilterList,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(FilterChipDefaults.IconSize),
                                    )
                                },
                                label = {
                                    Text(text = stringResource(MR.strings.action_filter))
                                },
                            )
                        }
                    }

                    HorizontalDivider()
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
                onWebViewClick = onWebViewClick,
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = onHelpClick.takeIf { viewModel.source is LocalSource },
                onClick = { row ->
                    // RK: tap toggles selection while bulk-selecting
                    if (bulkFavoriteState.selectionMode) {
                        adapter.toggleSelection(row)
                    } else {
                        navigator.push(MangaScreen(row.manga.id, true))
                    }
                },
                onLongClick = { row ->
                    // RK: long-press opens while bulk-selecting, otherwise the add/remove flow
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
            is BrowseSourceViewModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = onDismissRequest,
                    filters = state.filters,
                    onReset = viewModel::resetFilters,
                    onFilter = { viewModel.search(filters = state.filters) },
                    onUpdate = viewModel::setFilters,
                    // RK: Follows entry, only for a MangaDex source
                    onMangaDexFollowsClicked = if (viewModel.source.getMainSource<MangaDex>() != null) {
                        { navigator.push(MangaDexFollowsScreen(sourceId)) }
                    } else {
                        null
                    },
                    // RK: Random entry, only for a MangaDex source. The fetch is async, so
                    // the click only kicks it off; navigation happens in the LaunchedEffect below.
                    onMangaDexRandomClicked = if (viewModel.source.getMainSource<MangaDex>() != null) {
                        {
                            viewModel.onMangaDexRandom()
                            onDismissRequest()
                        }
                    } else {
                        null
                    },
                )
            }
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
                // RK --> the shared manga/novel remove dialog replaces Mihon's RemoveMangaDialog
                EntryRemoveDialog(
                    title = dialog.manga.title,
                    onDismissRequest = onDismissRequest,
                    onConfirm = {
                        viewModel.changeMangaFavorite(dialog.manga)
                    },
                )
                // RK <--
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

        // RK: bulk-selection dialogs
        BulkFavoriteDialogs(
            bulkFavoriteViewModel = bulkFavoriteViewModel,
            dialog = bulkFavoriteState.dialog,
        )

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow()
                .collectLatest {
                    when (it) {
                        is SearchType.Genre -> viewModel.searchGenre(it.txt)
                        is SearchType.Text -> viewModel.search(it.txt)
                    }
                }
        }
    }

    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))
    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}
