package reikai.presentation.browse.catalogue

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Refresh
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.AssistContentScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.online.all.MangaDex
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.ui.browse.source.browse.SourceFilterDialog
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import exh.md.follows.MangaDexFollowsScreen
import exh.source.getMainSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import mihon.app.di.appGraph
import mihon.presentation.core.util.collectAsLazyPagingItems
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import reikai.domain.source.model.SavedSearch
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.EntryAddDialogs
import reikai.presentation.browse.components.BulkSelectionToolbar
import reikai.presentation.novel.browse.NovelBrowseDialog
import reikai.presentation.novel.browse.NovelBrowseViewModel
import reikai.presentation.novel.browse.NovelBulkFavoriteViewModel
import reikai.presentation.novel.browse.NovelSourceFilterSheet
import reikai.presentation.novel.browse.NovelSourceSettingsSheet
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.core.common.Constants
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

/**
 * Which listing a manga catalogue opens on. Absent a query this has to name Popular out loud:
 * `Listing.valueOf(null)` is a Search, so a plain row tap would open on getSearchManga("").
 */
internal fun mangaListingQuery(startLatest: Boolean, initialQuery: String?): String? = when {
    startLatest -> BrowseSourceViewModel.Listing.Latest.query
    else -> initialQuery ?: BrowseSourceViewModel.Listing.Popular.query
}

/**
 * One source's catalogue, for a manga source and a light-novel source alike. [sourceKey] fixes the
 * content type before the screen opens, so this is the details surface's shape rather than the All-
 * first lists': a neutral state and behaviour with two adapters, and one chrome over both.
 *
 * The per-type branches below resolve their own models and supply the leaves nothing neutral can
 * cover: the filter sheet, the source settings, and where a tap goes.
 */
class EntryCatalogueScreen(
    val sourceKey: SourceKey,
    private val initialQuery: String? = null,
    /** Open on the source's Latest listing rather than Popular, for the Sources row's button. */
    private val startLatest: Boolean = false,
    /** Set when the screen was opened to choose what an entry migrates to. */
    private val migrateForId: Long? = null,
    /** Open showing this saved search, for a feed row built on one. */
    private val savedSearchId: Long? = null,
) : Screen(), AssistContentScreen {

    private var assistUrl: String? = null

    override fun onProvideAssistUrl() = assistUrl

    /** The manga source id, or null for a plugin. The incognito indicator keys on a manga source. */
    val mangaSourceId: Long? get() = (sourceKey as? SourceKey.Manga)?.id

    @Composable
    override fun Content() {
        when (sourceKey) {
            is SourceKey.Manga -> MangaCatalogue(sourceKey.id)
            is SourceKey.Novel -> NovelCatalogue(sourceKey.id)
        }
    }

    @Composable
    private fun MangaCatalogue(sourceId: Long) {
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current
        val context = LocalContext.current
        val viewModel = assistedMetroViewModel<BrowseSourceViewModel, BrowseSourceViewModel.Factory> {
            create(sourceId = sourceId, listingQuery = mangaListingQuery(startLatest, initialQuery))
        }
        val bulk = metroViewModel<BulkFavoriteViewModel>()
        // The graph's app-scoped binding, so the pick lands in the same handoff the models read.
        val pickHandoff = remember { context.appGraph.migrationPickHandoff }
        val adapter = remember(viewModel, bulk) {
            MangaBrowseAdapter(viewModel, bulk, migrateForId) { targetId ->
                migrateForId?.let { pickHandoff.offer(EntryId.Manga(it), targetId) }
                navigator.pop()
            }
        }
        val modelState by viewModel.state.collectAsState()
        val source = modelState.source
        if (source == null) {
            LoadingScreen()
            return
        }
        val isLocal = source is LocalSource
        val isMangaDex = remember(source) { source.getMainSource<MangaDex>() != null }

        // The Random button only kicks off the fetch; the id arrives later, and opening it is a new
        // catalogue on the same source with an "id:<uuid>" search.
        LaunchedEffect(modelState.randomMangaTarget) {
            val target = modelState.randomMangaTarget ?: return@LaunchedEffect
            viewModel.consumeRandomTarget()
            navigator.push(EntryCatalogueScreen(sourceKey, target))
        }

        Catalogue(
            behavior = adapter,
            onOpenEntry = { row -> navigator.push(MangaScreen(row.manga.id, true)) },
            onOpenEntryById = { id -> navigator.push(MangaScreen(id)) },
            onOpenSettings = { navigator.push(SourcePreferencesScreen(sourceId)) },
            onHelpClick = {
                uriHandler.openUri(if (isLocal) LocalSource.HELP_URL else Constants.URL_HELP)
            },
            localSourceHelp = { uriHandler.openUri(LocalSource.HELP_URL) }.takeIf { isLocal },
            onGenreSearch = viewModel::searchGenre,
        ) { onDismiss ->
            SourceFilterDialog(
                onDismissRequest = onDismiss,
                filters = modelState.filters,
                onReset = viewModel::resetFilters,
                onFilter = { viewModel.search(filters = modelState.filters) },
                onUpdate = viewModel::setFilters,
                onMangaDexFollowsClicked = { navigator.push(MangaDexFollowsScreen(sourceId)) }
                    .takeIf { isMangaDex },
                onMangaDexRandomClicked = {
                    viewModel.onMangaDexRandom()
                    onDismiss()
                }.takeIf { isMangaDex },
            )
        }
    }

    @Composable
    private fun NovelCatalogue(sourceId: String) {
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current
        val viewModel = assistedMetroViewModel<NovelBrowseViewModel, NovelBrowseViewModel.Factory> {
            create(sourceId = sourceId, initialQuery = initialQuery.orEmpty(), startLatest = startLatest)
        }
        val bulk = metroViewModel<NovelBulkFavoriteViewModel>()
        val adapter = remember(viewModel, bulk) {
            NovelBrowseAdapter(viewModel, bulk, sourceId, migrateForId) { navigator.pop() }
        }
        val modelState by viewModel.state.collectAsState()
        val source = modelState.source

        Catalogue(
            behavior = adapter,
            onOpenEntry = { row -> navigator.push(NovelScreen(sourceId, row.item.path)) },
            // A novel is addressed by source and path, so an id is resolved against the duplicates
            // the dialog was raised with, which are the only rows this can be called for.
            onOpenEntryById = { id ->
                (modelState.dialog as? NovelBrowseDialog.AddDuplicate)?.duplicates
                    ?.firstOrNull { it.novel.id == id }
                    ?.let { navigator.push(NovelScreen(it.novel.source, it.novel.url)) }
            },
            onOpenSettings = viewModel::openSettingsSheet,
            onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
            extraSheets = {
                if (modelState.settingsSheetOpen && source != null) {
                    NovelSourceSettingsSheet(source = source, onDismiss = viewModel::closeSettingsSheet)
                }
            },
        ) { onDismiss ->
            NovelSourceFilterSheet(
                filters = source?.filters,
                values = modelState.filterValues,
                onValueChange = viewModel::setFilterValue,
                onApply = viewModel::applyFilters,
                onReset = viewModel::resetFilters,
                onDismiss = onDismiss,
            )
        }
    }

    /**
     * The chrome both catalogues share: the toolbar, the listing chips, the body and every dialog the
     * neutral state can describe. What is left to the caller is the per-type filter sheet and where a
     * tap goes, because those are the two things no neutral state can hold.
     */
    @Composable
    private fun Catalogue(
        behavior: EntryBrowseBehavior,
        onOpenEntry: (EntryBrowseRow) -> Unit,
        onOpenEntryById: (Long) -> Unit,
        onOpenSettings: () -> Unit,
        onHelpClick: () -> Unit,
        localSourceHelp: (() -> Unit)? = null,
        onGenreSearch: ((String) -> Unit)? = null,
        extraSheets: @Composable () -> Unit = {},
        filterSheet: @Composable (onDismiss: () -> Unit) -> Unit,
    ) {
        val navigator = LocalNavigator.currentOrThrow
        val state by behavior.state.collectAsState()
        val rows = behavior.rows.collectAsLazyPagingItems()
        val snackbarHostState = remember { SnackbarHostState() }

        // Saved searches are the same feature on both content types, so they are held here rather than
        // in either model: the source is a key, and what a search holds is a string this never reads.
        val savedSearchModel = assistedMetroViewModel<SavedSearchViewModel, SavedSearchViewModel.Factory> {
            create(sourceKey = sourceKey)
        }
        val savedSearches by savedSearchModel.state.collectAsState()
        var savedSearchDialog by remember { mutableStateOf<SavedSearchDialog?>(null) }
        // Which chip reads as applied. Cleared by anything that replaces what it put on screen.
        var appliedSavedSearchId by remember { mutableStateOf<Long?>(null) }

        // Whether the screen's own saved search has been put on once. Separate from the chip state
        // above, which the reader clears by tapping anything else: keyed on that, re-applying became
        // a matter of the list re-emitting, which saving a new search on this very screen does.
        var openedWithSearch by rememberSaveable { mutableStateOf(false) }

        // Applied once the screen is up rather than before the model is built, which costs one
        // discarded page of the default listing and keeps the models free of a saved-search read.
        LaunchedEffect(savedSearches) {
            if (openedWithSearch) return@LaunchedEffect
            val search = savedSearchId?.let { id -> savedSearches.firstOrNull { it.id == id } } ?: return@LaunchedEffect
            openedWithSearch = true
            appliedSavedSearchId = search.id
            behavior.applySearch(search.query, search.filtersJson)
        }

        LaunchedEffect(Unit) {
            queryEvent.receiveAsFlow().collectLatest {
                when (it) {
                    is SearchType.Text -> behavior.search(it.txt)
                    is SearchType.Genre -> onGenreSearch?.invoke(it.txt) ?: behavior.search(it.txt)
                }
            }
        }

        val loaded = state as? EntryBrowseScreenState.Loaded
        if (loaded == null) {
            SourceUnavailable(state, navigator::pop)
            return
        }

        LaunchedEffect(loaded.webUrl) { assistUrl = loaded.webUrl }

        // Coming back from the WebView re-runs the listing, so clearing a Cloudflare challenge there
        // does not leave the reader staring at the error they left. Survives the activity stop.
        var pendingWebViewRetry by rememberSaveable { mutableStateOf(false) }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            if (pendingWebViewRetry) {
                pendingWebViewRetry = false
                rows.refresh()
            }
        }
        val onWebViewClick: (String?) -> Unit = f@{ challengeUrl ->
            val url = challengeUrl ?: loaded.webUrl ?: return@f
            pendingWebViewRetry = true
            navigator.push(WebViewScreen(url = url, initialTitle = loaded.sourceName, sourceId = mangaSourceId))
        }

        BackHandler(enabled = loaded.selectionMode) { behavior.setSelectionMode(false) }

        val navigateUp = {
            if (!loaded.isUserQuery && loaded.query != null) {
                behavior.setQuery(null)
            } else {
                navigator.pop()
            }
            Unit
        }

        Scaffold(
            topBar = { scrollBehavior ->
                Column(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surface)
                        // The grid scrolls under this bar, so without a pointer consumer here a
                        // drag starting on the toolbar or the chips grabs the list behind them.
                        .pointerInput(Unit) {},
                ) {
                    if (loaded.selectionMode) {
                        BulkSelectionToolbar(
                            selectedCount = loaded.selectedKeys.size,
                            onClickClearSelection = { behavior.setSelectionMode(false) },
                            onChangeCategoryClick = behavior::addSelectionToLibrary,
                            onSelectAll = { behavior.selectAll(rows.itemSnapshotList.items) },
                            onReverseSelection = { behavior.invertSelection(rows.itemSnapshotList.items) },
                        )
                    } else {
                        EntryCatalogueToolbar(
                            title = loaded.sourceName,
                            searchQuery = loaded.query,
                            onSearchQueryChange = behavior::setQuery,
                            displayMode = (loaded.rowStyle as? EntryBrowseRowStyle.Standard)?.displayMode,
                            onDisplayModeChange = behavior::setDisplayMode,
                            hasWebView = loaded.webUrl != null,
                            hasSettings = loaded.hasSettings,
                            navigateUp = navigateUp,
                            onWebViewClick = { onWebViewClick(null) },
                            onHelpClick = onHelpClick,
                            onSettingsClick = onOpenSettings,
                            onSearch = {
                                appliedSavedSearchId = null
                                behavior.search(it)
                            },
                            onCloseSearch = {
                                appliedSavedSearchId = null
                                behavior.search(null)
                            },
                            scrollBehavior = scrollBehavior,
                            onToggleSelectionMode = { behavior.setSelectionMode(true) },
                            // Only offered when there is something to save: a committed search, or
                            // filters the reader has changed from the source's own defaults.
                            onSaveSearchClick = { savedSearchDialog = SavedSearchDialog.Create }
                                .takeIf {
                                    loaded.filtersActive || loaded.listing is EntryBrowseListing.Search
                                },
                        )
                    }
                    ListingChips(
                        loaded = loaded,
                        behavior = behavior,
                        savedSearches = savedSearches,
                        appliedSavedSearchId = appliedSavedSearchId,
                        onApplySavedSearch = { search ->
                            appliedSavedSearchId = search.id
                            behavior.applySearch(search.query, search.filtersJson)
                        },
                        onLongClickSavedSearch = { search ->
                            savedSearchDialog = SavedSearchDialog.Delete(search.id, search.name)
                        },
                        onClearSavedSearch = { appliedSavedSearchId = null },
                    )
                    HorizontalDivider()
                }
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            EntryBrowseCatalogue(
                rows = rows,
                rowStyle = loaded.rowStyle,
                selectedKeys = loaded.selectedKeys,
                // Both modes make a long press preview the entry rather than grab it.
                longPressOpensEntry = loaded.selectionMode || loaded.capabilities.migrationPick != null,
                snackbarHostState = snackbarHostState,
                contentPadding = contentPadding,
                onWebViewClick = onWebViewClick,
                onHelpClick = onHelpClick,
                onLocalSourceHelpClick = localSourceHelp,
                onClick = { row ->
                    val pick = loaded.capabilities.migrationPick
                    when {
                        pick != null -> pick.pick(row) {}
                        loaded.selectionMode -> behavior.toggleSelection(row)
                        else -> onOpenEntry(row)
                    }
                },
                onLongClick = { row ->
                    // Selecting or picking a migration target, a long press previews the entry; the
                    // add flow would favourite something the reader is only inspecting.
                    if (loaded.capabilities.migrationPick != null || loaded.selectionMode) {
                        onOpenEntry(row)
                    } else {
                        behavior.onRowLongClick(row)
                    }
                },
            )
        }

        // The two this surface owns alone; the four a long press can raise are shared.
        when (val dialog = loaded.dialog) {
            EntryBrowseDialog.Filter -> filterSheet(behavior::dismissDialog)
            is EntryBrowseDialog.SelectionCategories -> ChangeCategoryDialog(
                initialSelection = dialog.initialSelection,
                onDismissRequest = behavior::dismissDialog,
                onEditCategories = { navigator.push(CategoryScreen()) },
                onConfirm = { include, _ -> behavior.setSelectionCategories(include) },
            )
            else -> Unit
        }
        EntryAddDialogs(
            dialog = loaded.dialog,
            contentType = sourceKey.contentType,
            onDismissRequest = behavior::dismissDialog,
            onConfirmRemove = behavior::confirmRemove,
            onConfirmCategories = behavior::confirmCategories,
            onConfirmAddDuplicate = behavior::confirmAddDuplicate,
            onAddToGroup = behavior::addToGroup,
            onStartMigrate = behavior::startMigrate,
            onOpenEntryById = onOpenEntryById,
        )

        when (val dialog = savedSearchDialog) {
            null -> Unit
            SavedSearchDialog.Create -> SavedSearchCreateDialog(
                onDismissRequest = { savedSearchDialog = null },
                onCreate = { name -> savedSearchModel.save(name, behavior.captureSearch()) },
                existingNames = savedSearches.map { it.name },
            )
            is SavedSearchDialog.Delete -> SavedSearchDeleteDialog(
                name = dialog.name,
                onDismissRequest = { savedSearchDialog = null },
                onDelete = { savedSearchModel.delete(dialog.id) },
            )
        }

        extraSheets()
    }

    @Composable
    private fun ListingChips(
        loaded: EntryBrowseScreenState.Loaded,
        behavior: EntryBrowseBehavior,
        savedSearches: List<SavedSearch>,
        appliedSavedSearchId: Long?,
        onApplySavedSearch: (SavedSearch) -> Unit,
        onLongClickSavedSearch: (SavedSearch) -> Unit,
        onClearSavedSearch: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MaterialTheme.padding.small),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            ListingChip(
                selected = loaded.listing == EntryBrowseListing.Popular,
                icon = Icons.Outlined.Favorite,
                label = stringResource(MR.strings.popular),
                onClick = {
                    onClearSavedSearch()
                    behavior.setListing(EntryBrowseListing.Popular)
                },
            )
            if (loaded.supportsLatest) {
                ListingChip(
                    selected = loaded.listing == EntryBrowseListing.Latest,
                    icon = Icons.Outlined.NewReleases,
                    label = stringResource(MR.strings.latest),
                    onClick = {
                        onClearSavedSearch()
                        behavior.setListing(EntryBrowseListing.Latest)
                    },
                )
            }
            if (loaded.hasFilters) {
                ListingChip(
                    selected = loaded.filtersActive,
                    icon = Icons.Outlined.FilterList,
                    label = stringResource(MR.strings.action_filter),
                    onClick = {
                        // Opening the sheet, not only applying from it: whatever the reader does next
                        // is theirs, and a chip still lit would claim results that are no longer its.
                        onClearSavedSearch()
                        behavior.openFilterSheet()
                    },
                )
            }
            savedSearches.forEach { search ->
                SavedSearchChip(
                    // Lit only until something replaces what it put on screen. Not derived from the
                    // listing: a filter-only search leaves a novel source on Popular, showing its
                    // own results, where the manga twin lands on a search.
                    selected = search.id == appliedSavedSearchId,
                    label = search.name,
                    onClick = { onApplySavedSearch(search) },
                    onLongClick = { onLongClickSavedSearch(search) },
                )
            }
        }
    }

    @Composable
    private fun ListingChip(
        selected: Boolean,
        icon: ImageVector,
        label: String,
        onClick: () -> Unit,
    ) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            leadingIcon = { Icon(icon, null, Modifier.size(FilterChipDefaults.IconSize)) },
            label = { Text(text = label) },
        )
    }

    /**
     * A saved search: tap to apply, long-press to delete.
     *
     * The gesture rides the label rather than the chip's own modifier, because a chip applies its
     * clickable inside whatever modifier it is given. That makes the chip's handler the deeper node,
     * so a long press on the outside is swallowed before it can be seen.
     */
    @Composable
    private fun SavedSearchChip(
        selected: Boolean,
        label: String,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            leadingIcon = {
                Icon(Icons.Outlined.BookmarkBorder, null, Modifier.size(FilterChipDefaults.IconSize))
            },
            label = {
                Text(
                    text = label,
                    modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
                )
            },
        )
    }

    /** Loading, or a source that cannot serve a catalogue: a missing extension has no retry, a plugin
     *  that threw while loading does. */
    @Composable
    private fun SourceUnavailable(state: EntryBrowseScreenState, navigateUp: () -> Unit) {
        if (state is EntryBrowseScreenState.Loading) {
            LoadingScreen()
            return
        }
        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    // Named even when it cannot load, so the reader can tell which source this was.
                    title = (state as? EntryBrowseScreenState.SourceMissing)?.label,
                    navigateUp = navigateUp,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            when (state) {
                is EntryBrowseScreenState.SourceMissing -> EmptyScreen(
                    message = stringResource(MR.strings.source_not_installed, state.label),
                    modifier = Modifier.padding(contentPadding),
                )
                is EntryBrowseScreenState.SourceFailed -> EmptyScreen(
                    message = state.message,
                    modifier = Modifier.padding(contentPadding),
                    actions = listOf(
                        EmptyScreenAction(MR.strings.action_retry, Icons.Outlined.Refresh, state.reload),
                    ),
                )
                else -> Unit
            }
        }
    }

    /** Search the catalogue this screen sits under, from the details page it opened. */
    suspend fun search(query: String) = queryEvent.send(SearchType.Text(query))

    suspend fun searchGenre(name: String) = queryEvent.send(SearchType.Genre(name))

    companion object {
        private val queryEvent = Channel<SearchType>()
    }

    private sealed class SearchType(val txt: String) {
        class Text(txt: String) : SearchType(txt)
        class Genre(txt: String) : SearchType(txt)
    }
}

/** The two saved-search dialogs the catalogue raises itself, neither of which any model owns. */
private sealed interface SavedSearchDialog {
    data object Create : SavedSearchDialog
    data class Delete(val id: Long, val name: String) : SavedSearchDialog
}
