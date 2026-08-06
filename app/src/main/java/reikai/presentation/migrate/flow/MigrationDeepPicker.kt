package reikai.presentation.migrate.flow

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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.core.util.ifSourcesLoaded
import eu.kanade.presentation.browse.BrowseSourceContent
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreenModel
import eu.kanade.tachiyomi.ui.browse.source.browse.SourceFilterDialog
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import mihon.presentation.core.util.collectAsLazyPagingItems
import reikai.domain.entry.EntryId
import reikai.presentation.novel.browse.NovelBrowseScreen
import tachiyomi.core.common.Constants
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.injectLazy

// File-level rather than a Screen field: Voyager serializes Screen instances, and a composable-body
// Injekt.get is against the screen conventions for net-new code.
private val pickHandoff: MigrationPickHandoff by injectLazy()

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
            NovelBrowseScreen(sourceKey, query, migratePickFor = entry.id.rawId),
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
        val screenModel = rememberScreenModel { BrowseSourceScreenModel(sourceId, query) }
        val state by screenModel.state.collectAsState()
        val snackbarHostState = remember { SnackbarHostState() }

        Scaffold(
            topBar = { scrollBehavior ->
                SearchToolbar(
                    searchQuery = state.toolbarQuery ?: "",
                    onChangeSearchQuery = screenModel::setToolbarQuery,
                    onClickCloseSearch = navigator::pop,
                    onSearch = screenModel::search,
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.action_filter)) },
                    icon = { Icon(Icons.Outlined.FilterList, contentDescription = null) },
                    onClick = screenModel::openFilterSheet,
                    modifier = Modifier.animateFloatingActionButton(
                        visible = state.filters.isNotEmpty(),
                        alignment = Alignment.BottomEnd,
                    ),
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { contentPadding ->
            BrowseSourceContent(
                source = screenModel.source,
                mangaList = screenModel.mangaPagerFlowFlow.collectAsLazyPagingItems(),
                columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = screenModel.displayMode,
                // The target picker keeps the plain grid; the enhanced rows are a browse-only surface.
                useEhentaiView = false,
                snackbarHostState = snackbarHostState,
                contentPadding = contentPadding,
                // Live, not an empty lambda: this is the error screen's only way out of a source that
                // is blocking the request, and the picker is exactly where a user meets one.
                onWebViewClick = f@{
                    val http = screenModel.source as? HttpSource ?: return@f
                    navigator.push(
                        WebViewScreen(url = http.getHomeUrl(), initialTitle = http.name, sourceId = http.id),
                    )
                },
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) },
                onMangaClick = {
                    pickHandoff.offer(EntryId.Manga(entryRawId), it.id)
                    navigator.pop()
                },
                // Long-press opens the entry, so a candidate can be checked before choosing it.
                onMangaLongClick = { navigator.push(MangaScreen(it.id, true)) },
            )
        }

        // The Filter button set this and nothing rendered it, so filtering a source while picking a
        // target silently did nothing.
        if (state.dialog is BrowseSourceScreenModel.Dialog.Filter) {
            SourceFilterDialog(
                onDismissRequest = { screenModel.setDialog(null) },
                filters = state.filters,
                onReset = screenModel::resetFilters,
                onFilter = { screenModel.search(filters = state.filters) },
                onUpdate = screenModel::setFilters,
            )
        }
    }
}
