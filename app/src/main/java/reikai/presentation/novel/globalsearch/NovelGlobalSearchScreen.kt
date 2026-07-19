package reikai.presentation.novel.globalsearch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.browse.components.GlobalSearchLoadingResultItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.launch
import reikai.novel.host.NovelItem
import reikai.novel.source.NovelSource
import reikai.presentation.browse.EntryBrowseGridCell
import reikai.presentation.browse.EntrySearchCardRow
import reikai.presentation.browse.EntrySearchSection
import reikai.presentation.browse.EntrySearchSourceFilterChips
import reikai.presentation.browse.components.BulkSelectionToolbar
import reikai.presentation.browse.toEntryBrowseUi
import reikai.presentation.novel.browse.DuplicateNovelDialog
import reikai.presentation.novel.browse.NovelBrowseDialog
import reikai.presentation.novel.browse.NovelBrowseScreen
import reikai.presentation.novel.browse.NovelBulkFavoriteScreenModel
import reikai.presentation.novel.browse.RemoveNovelDialog
import reikai.presentation.novel.browse.SelectedNovel
import reikai.presentation.novel.details.NovelCategoryDialog
import reikai.presentation.novel.details.NovelDetailsDialog
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.globalsearch.NovelGlobalSearchScreenModel.SourceFilter
import reikai.presentation.novel.migrate.NovelMigrateHost
import reikai.presentation.novel.migrate.rememberNovelMigrateController
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Cross-source light-novel search, the novel twin of Mihon's manga global search. A Pinned / All /
 * Has-results chip row drives which sources are searched and shown; each source has its own row that
 * shows a spinner, then its covers / "no results" / an error as it completes. The source header is
 * tappable to open that source's full browse pre-filled with the query. State lives in
 * [NovelGlobalSearchScreenModel].
 */
class NovelGlobalSearchScreen(
    private val initialQuery: String = "",
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelGlobalSearchScreenModel(initialQuery) }
        val state by screenModel.state.collectAsState()
        var searchQuery by rememberSaveable { mutableStateOf(initialQuery) }
        // RK: shared bulk-selection add-to-library
        val bulkModel = rememberScreenModel { NovelBulkFavoriteScreenModel() }
        val bulkState by bulkModel.state.collectAsState()

        BackHandler(enabled = bulkState.selectionMode) { bulkModel.backHandler() }

        Scaffold(
            topBar = { scrollBehavior ->
                // RK: while bulk-selecting, the selection bar replaces the search toolbar (matches manga).
                if (bulkState.selectionMode) {
                    BulkSelectionToolbar(
                        selectedCount = bulkState.selection.size,
                        onClickClearSelection = bulkModel::toggleSelectionMode,
                        onChangeCategoryClick = { bulkModel.addFavorite(state.favoritedKeys) },
                    )
                } else {
                    SearchToolbar(
                        searchQuery = searchQuery,
                        onChangeSearchQuery = { searchQuery = it ?: "" },
                        navigateUp = navigator::pop,
                        placeholderText = stringResource(MR.strings.action_search),
                        onSearch = screenModel::search,
                        onClickCloseSearch = navigator::pop,
                        actions = {
                            AppBarActions(
                                listOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_bulk_select),
                                        icon = Icons.Outlined.Checklist,
                                        onClick = bulkModel::toggleSelectionMode,
                                    ),
                                ),
                            )
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
        ) { contentPadding ->
            NovelGlobalSearchResults(
                state = state,
                contentPadding = contentPadding,
                selection = bulkState.selection,
                onSetSourceFilter = screenModel::setSourceFilter,
                onToggleHasResults = screenModel::toggleHasResults,
                // RK: tap toggles selection while bulk-selecting, long-press opens details (mirrors manga)
                onResultClick = { source, item ->
                    if (bulkState.selectionMode) {
                        bulkModel.toggleSelection(source.id, item)
                    } else {
                        navigator.push(NovelScreen(source.id, item.path))
                    }
                },
                onClickSource = { source -> navigator.push(NovelBrowseScreen(source.id, state.query)) },
                onResultLongClick = { source, item ->
                    if (bulkState.selectionMode) {
                        navigator.push(NovelScreen(source.id, item.path))
                    } else {
                        screenModel.onLongClickItem(item, source.id)
                    }
                },
            )
        }

        val migrateScope = rememberCoroutineScope()
        val migrateController = rememberNovelMigrateController()
        when (val dialog = state.dialog) {
            is NovelBrowseDialog.AddDuplicate -> DuplicateNovelDialog(
                duplicates = dialog.duplicates,
                sourceNames = dialog.sourceNames,
                sourceSites = dialog.sourceSites,
                onDismissRequest = screenModel::dismissDialog,
                onConfirm = { screenModel.addFromDuplicate(dialog.item, dialog.sourceId) },
                onOpenNovel = { navigator.push(NovelScreen(it.source, it.url)) },
                onMigrate = { dup ->
                    migrateScope.launch {
                        screenModel.materializeForMigrate(dialog.item, dialog.sourceId)
                            ?.let { migrateController.start(dup, it) }
                    }
                },
                groupIdByNovelId = dialog.groupIdByNovelId,
                onAddToGroup = { selectedIds: List<Long> ->
                    screenModel.addToExistingGroup(dialog.item, dialog.sourceId, selectedIds)
                }.takeIf { dialog.suggestGroup },
            )
            is NovelBrowseDialog.ChangeCategory -> NovelCategoryDialog(
                dialog = NovelDetailsDialog.ChangeCategory(dialog.allCategories, dialog.currentCategoryIds),
                onDismiss = screenModel::dismissDialog,
                onConfirm = { screenModel.applyCategories(dialog.novelId, it) },
            )
            is NovelBrowseDialog.RemoveNovel -> RemoveNovelDialog(
                title = dialog.item.name,
                onDismiss = screenModel::dismissDialog,
                onConfirm = { screenModel.confirmRemove(dialog.item, dialog.sourceId) },
            )
            null -> {}
        }
        NovelMigrateHost(migrateController)

        // RK: bulk add-to-library category picker, one choice applied to the whole selection.
        when (val bulkDialog = bulkState.dialog) {
            is NovelBulkFavoriteScreenModel.Dialog.ChangeCategory -> NovelCategoryDialog(
                dialog = NovelDetailsDialog.ChangeCategory(bulkDialog.categories, emptySet()),
                onDismiss = { bulkModel.setDialog(null) },
                onConfirm = { bulkModel.setNovelsCategories(bulkDialog.items, it) },
            )
            null -> {}
        }
    }
}

/**
 * The shared cross-source results body: the Pinned / All / Has-results chips and one row per source.
 * The migration screen ([reikai.presentation.novel.migrate.NovelMigrationListScreen]) runs the same
 * per-source fan-out per row; this composable backs the standalone global search.
 */
@Composable
internal fun NovelGlobalSearchResults(
    state: NovelGlobalSearchState,
    contentPadding: PaddingValues,
    onSetSourceFilter: (SourceFilter) -> Unit,
    onToggleHasResults: () -> Unit,
    onResultClick: (NovelSource, NovelItem) -> Unit,
    onClickSource: ((NovelSource) -> Unit)?,
    sourceFilter: (SourceSearchResult) -> Boolean = { true },
    // Long-press handler; null falls back to [onResultClick] (migrate mode, where long-press = tap).
    onResultLongClick: ((NovelSource, NovelItem) -> Unit)? = null,
    // Bulk-selection highlight; empty in migrate mode (no selection there).
    selection: List<SelectedNovel> = emptyList(),
) {
    val layoutDirection = LocalLayoutDirection.current
    Column(modifier = Modifier.padding(top = contentPadding.calculateTopPadding())) {
        EntrySearchSourceFilterChips(
            isPinnedOnly = state.sourceFilter == SourceFilter.PinnedOnly,
            onlyShowHasResults = state.onlyShowHasResults,
            showSourceFilter = true,
            onSelectPinnedOnly = { onSetSourceFilter(SourceFilter.PinnedOnly) },
            onSelectAll = { onSetSourceFilter(SourceFilter.All) },
            onToggleResults = onToggleHasResults,
        )
        // Search-completion bar, shown only while some sources are still loading (mirrors manga's
        // GlobalSearchToolbar). Hidden before the first result lands and once every source finishes.
        if (state.progress in 1 until state.total) {
            LinearProgressIndicator(
                progress = { state.progress / state.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // The has-results filter hides sources still loading / errored / empty.
        val visibleResults = state.results.filter { sourceFilter(it) && it.isVisible(state.onlyShowHasResults) }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection),
                end = contentPadding.calculateEndPadding(layoutDirection),
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            items(items = visibleResults, key = { it.source.id }) { result ->
                SourceSection(
                    result = result,
                    favoritedKeys = state.favoritedKeys,
                    onResultClick = { onResultClick(result.source, it) },
                    onResultLongClick = { (onResultLongClick ?: onResultClick)(result.source, it) },
                    onClickSource = onClickSource?.let { handler -> { handler(result.source) } },
                    selection = selection,
                )
            }
        }
    }
}

@Composable
internal fun SourceSection(
    result: SourceSearchResult,
    favoritedKeys: Set<Pair<String, String>>,
    onResultClick: (NovelItem) -> Unit,
    onResultLongClick: (NovelItem) -> Unit,
    // null in migrate mode, where opening the source's browse would be a dead-end.
    onClickSource: (() -> Unit)?,
    selection: List<SelectedNovel> = emptyList(),
) {
    val context = LocalContext.current
    val lang = result.source.lang.takeIf { it.isNotBlank() }
        ?.let { LocaleHelper.getSourceDisplayName(it, context) }.orEmpty()
    // Tap the header to open this source's full browse pre-filled with the query (when enabled).
    EntrySearchSection(
        title = result.source.name,
        subtitle = lang,
        onClick = onClickSource,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        when (val s = result.state) {
            is SearchState.Loading -> GlobalSearchLoadingResultItem()
            is SearchState.Error -> Text(
                text = s.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            is SearchState.Success -> EntrySearchCardRow(
                entries = s.novels,
                key = { it.path },
                toUi = {
                    it.toEntryBrowseUi(
                        inLibrary = (result.source.id to it.path) in favoritedKeys,
                        site = result.source.site,
                    )
                },
                onClick = onResultClick,
                onLongClick = onResultLongClick,
                isSelected = { item ->
                    selection.fastAny { it.sourceId == result.source.id && it.item.path == item.path }
                },
            )
        }
    }
}
