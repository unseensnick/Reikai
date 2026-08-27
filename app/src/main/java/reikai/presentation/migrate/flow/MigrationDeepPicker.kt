package reikai.presentation.migrate.flow

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.animateFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.ui.browse.source.browse.SourceFilterDialog
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import mihon.app.di.appGraph
import mihon.presentation.core.util.collectAsLazyPagingItems
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.catalogue.EntryBrowseCatalogue
import reikai.presentation.browse.catalogue.EntryBrowseRowStyle
import reikai.presentation.browse.catalogue.EntryBrowseScreenState
import reikai.presentation.browse.catalogue.EntryCatalogueScreen
import reikai.presentation.browse.catalogue.MangaBrowseAdapter
import reikai.presentation.browse.catalogue.manga
import tachiyomi.core.common.Constants
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

/**
 * Push a full browse of one source to choose a migration target from, when the inline strips are not
 * enough (a title that only turns up behind filters or deeper in the results).
 *
 * Per content type, because browsing a source is a per-type screen; what they share is where the
 * pick goes, [MigrationPickHandoff]. Returns false when the source key makes no sense for the type,
 * so the caller can say so instead of pushing a screen that cannot load.
 */
internal fun openDeepPicker(
    navigator: cafe.adriel.voyager.navigator.Navigator,
    entry: MigrationEntry,
    sourceKey: String,
    query: String,
): Boolean {
    when (entry.id) {
        is EntryId.Manga -> {
            val sourceId = sourceKey.toLongOrNull() ?: return false
            navigator.push(MigrationDeepPickerScreen(entry.id.rawId, sourceId, query))
        }
        is EntryId.Novel -> navigator.push(
            EntryCatalogueScreen(SourceKey.Novel(sourceKey), query, migrateForId = entry.id.rawId),
        )
    }
    return true
}

/** The manga half: Mihon's browse content for one source, with a tap handing the pick back. */
class MigrationDeepPickerScreen(
    private val entryRawId: Long,
    private val sourceId: Long,
    private val query: String,
) : Screen() {

    @Composable
    override fun Content() {
        if (!ifSourcesLoaded()) {
            LoadingScreen()
            return
        }
        val navigator = LocalNavigator.currentOrThrow
        val uriHandler = LocalUriHandler.current
        // The graph's app-scoped binding, so the pick lands in the same handoff the models read.
        val pickHandoff = LocalContext.current.appGraph.migrationPickHandoff
        val viewModel = assistedMetroViewModel<BrowseSourceViewModel, BrowseSourceViewModel.Factory> {
            create(sourceId = sourceId, listingQuery = query)
        }
        val state by viewModel.state.collectAsState()
        // The picker never bulk-selects, but the adapter reads a selection off this model.
        val bulk = metroViewModel<BulkFavoriteViewModel>()
        val adapter = remember(viewModel, bulk) { MangaBrowseAdapter(viewModel, bulk) }
        val entries = adapter.rows.collectAsLazyPagingItems()
        val browseState by adapter.state.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        Scaffold(
            topBar = { scrollBehavior ->
                SearchToolbar(
                    searchQuery = state.toolbarQuery ?: "",
                    onChangeSearchQuery = viewModel::setToolbarQuery,
                    onClickCloseSearch = navigator::pop,
                    onSearch = viewModel::search,
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.action_filter)) },
                    icon = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
                    onClick = viewModel::openFilterSheet,
                    modifier = Modifier.animateFloatingActionButton(
                        visible = state.filters.isNotEmpty(),
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            val loaded = browseState as? EntryBrowseScreenState.Loaded
            if (loaded == null) {
                LoadingScreen(Modifier.padding(contentPadding))
                return@Scaffold
            }
            EntryBrowseCatalogue(
                rows = entries,
                // The target picker keeps the plain grid; the enhanced rows are a browse-only surface.
                rowStyle = EntryBrowseRowStyle.Standard(viewModel.displayMode),
                selectedKeys = emptySet(),
                snackbarHostState = snackbarHostState,
                contentPadding = contentPadding,
                // Live, not an empty lambda: this is the error screen's only way out of a source that
                // is blocking the request, and the picker is exactly where a user meets one.
                onWebViewClick = f@{
                    val http = viewModel.source as? HttpSource ?: return@f
                    navigator.push(
                        WebViewScreen(url = http.getHomeUrl(), initialTitle = http.name, sourceId = http.id),
                    )
                },
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) }
                    .takeIf { viewModel.source is LocalSource },
                onClick = { row ->
                    pickHandoff.offer(EntryId.Manga(entryRawId), row.manga.id)
                    navigator.pop()
                },
                // Long-press opens the entry, so a candidate can be checked before choosing it.
                onLongClick = { row -> navigator.push(MangaScreen(row.manga.id, true)) },
            )
        }

        // The Filter button set this and nothing rendered it, so filtering a source while picking a
        // target silently did nothing.
        if (state.dialog is BrowseSourceViewModel.Dialog.Filter) {
            SourceFilterDialog(
                onDismissRequest = { viewModel.setDialog(null) },
                filters = state.filters,
                onReset = viewModel::resetFilters,
                onFilter = { viewModel.search(filters = state.filters) },
                onUpdate = viewModel::setFilters,
            )
        }
    }
}
