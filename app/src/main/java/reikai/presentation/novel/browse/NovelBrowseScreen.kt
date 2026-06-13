package reikai.presentation.novel.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.browse.components.BrowseSourceLoadingItem
import eu.kanade.presentation.library.components.CommonMangaItemDefaults
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import reikai.presentation.novel.details.NovelScreen
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
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelBrowseScreenModel(sourceId) }
        val state by screenModel.state.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        var searchQuery by rememberSaveable { mutableStateOf<String?>(null) }
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
        // listing routes the error through the EmptyScreen body instead.
        val errorString = state.error
        LaunchedEffect(errorString) {
            if (errorString != null && state.novels.isNotEmpty()) {
                snackbarHostState.showSnackbar(message = errorString, duration = SnackbarDuration.Short)
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
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
                        },
                        scrollBehavior = scrollBehavior,
                    )

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
                                screenModel.setListing(NovelBrowseState.Listing.Popular)
                            },
                        )
                        ListingChip(
                            selected = !searching && state.listing == NovelBrowseState.Listing.Latest,
                            icon = Icons.Outlined.NewReleases,
                            label = stringResource(MR.strings.latest),
                            onClick = {
                                searchQuery = null
                                screenModel.setListing(NovelBrowseState.Listing.Latest)
                            },
                        )
                        if (state.source?.filters?.isNotEmpty() == true) {
                            ListingChip(
                                selected = false,
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
                contentPadding = contentPadding,
                onRetry = screenModel::retry,
                onWebViewClick = onWebViewClick,
                onResultClick = { navigator.push(NovelScreen(sourceId, it.path)) },
                onLoadMore = screenModel::loadMore,
            )
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
    contentPadding: PaddingValues,
    onRetry: () -> Unit,
    onWebViewClick: () -> Unit,
    onResultClick: (reikai.novel.host.NovelItem) -> Unit,
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
        else -> {
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                columns = GridCells.Adaptive(128.dp),
                state = gridState,
                contentPadding = contentPadding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridVerticalSpacer),
                horizontalArrangement = Arrangement.spacedBy(CommonMangaItemDefaults.GridHorizontalSpacer),
            ) {
                items(items = state.novels, key = { it.path }) { item ->
                    NovelBrowseGridCell(
                        item = item,
                        inLibrary = (sourceId to item.path) in state.favoritedKeys,
                        onClick = { onResultClick(item) },
                        onLongClick = { onResultClick(item) },
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
