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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import reikai.domain.library.ContentType
import tachiyomi.core.common.Constants
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.source.local.LocalSource

/**
 * The manga deep target picker: full browse of one source (filters, pagination, webview) whose tap
 * hands the pick back to the migration list beneath it, or opens the shared migrate dialog when
 * there is none (the single-entry search route). The novel deep path is `NovelBrowseScreen`'s pick
 * mode; this screen runs on Mihon's live `BrowseSourceScreenModel`, which is manga-only.
 */
class MigrationDeepSearchScreen(
    private val currentMangaId: Long,
    private val sourceId: Long,
    private val query: String?,
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
        var dialogTargetId by rememberSaveable { mutableStateOf<Long?>(null) }

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
        ) { paddingValues ->
            BrowseSourceContent(
                source = screenModel.source,
                mangaList = screenModel.mangaPagerFlowFlow.collectAsLazyPagingItems(),
                columns = screenModel.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = screenModel.displayMode,
                // RK: migration target picker keeps the plain grid/list; enhanced rows are browse-only.
                useEhentaiView = false,
                snackbarHostState = snackbarHostState,
                contentPadding = paddingValues,
                onWebViewClick = {
                    val source = screenModel.source as? HttpSource ?: return@BrowseSourceContent
                    navigator.push(
                        WebViewScreen(
                            url = source.getHomeUrl(),
                            initialTitle = source.name,
                            sourceId = source.id,
                        ),
                    )
                },
                onHelpClick = { uriHandler.openUri(Constants.URL_HELP) },
                onLocalSourceHelpClick = { uriHandler.openUri(LocalSource.HELP_URL) },
                onMangaClick = { picked ->
                    val listScreen = navigator.items
                        .filterIsInstance<EntryMigrationListScreen>()
                        .lastOrNull()
                    if (listScreen != null) {
                        listScreen.addMatchOverride(currentRawId = currentMangaId, targetRawId = picked.id)
                        navigator.popUntil { it is EntryMigrationListScreen }
                    } else {
                        dialogTargetId = picked.id
                    }
                },
                onMangaLongClick = { navigator.push(MangaScreen(it.id, true)) },
            )
        }

        when (val dialog = state.dialog) {
            is BrowseSourceScreenModel.Dialog.Filter -> {
                SourceFilterDialog(
                    onDismissRequest = { screenModel.setDialog(null) },
                    filters = state.filters,
                    onReset = screenModel::resetFilters,
                    onFilter = { screenModel.search(filters = state.filters) },
                    onUpdate = screenModel::setFilters,
                )
            }
            else -> {}
        }

        val targetId = dialogTargetId
        if (targetId != null) {
            EntryMigrateFor(
                contentType = ContentType.MANGA,
                currentId = currentMangaId,
                targetId = targetId,
                onDismissRequest = { dialogTargetId = null },
                onFinished = { replaced ->
                    dialogTargetId = null
                    // Land on the migrated-to entry like the search route: pop the picker chain, and
                    // on a replace swap out the origin's now-stale details beneath it.
                    navigator.popUntil { it !is MigrationDeepSearchScreen && it !is EntryMigrationSearchScreen }
                    if (replaced && navigator.lastItem is MangaScreen) {
                        navigator.replace(MangaScreen(targetId))
                    } else {
                        navigator.push(MangaScreen(targetId))
                    }
                },
            )
        }
    }
}
