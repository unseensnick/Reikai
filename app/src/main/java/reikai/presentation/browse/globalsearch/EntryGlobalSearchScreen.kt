package reikai.presentation.browse.globalsearch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.components.GlobalSearchToolbar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchViewModel
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.TravelExplore
import reikai.domain.library.ContentType
import reikai.presentation.browse.BulkCategoryDialogs
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.EntryAddDialogs
import reikai.presentation.browse.EntryBulkFavoriteViewModel
import reikai.presentation.browse.SearchResultSection
import reikai.presentation.browse.catalogue.EntryCatalogueScreen
import reikai.presentation.browse.listedEntries
import reikai.presentation.browse.selectionTitle
import reikai.presentation.components.ContentTypeTabs
import reikai.presentation.novel.browse.NovelBulkFavoriteViewModel
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.globalsearch.NovelGlobalSearchViewModel
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.EmptyScreenAction
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * One cross-source search over both content types, with the content-type chip as a predicate over
 * the results rather than a switch between two screens.
 *
 * The query, which sources it covers, the order results land in and how many run at once live in
 * [GlobalSearchEngine]; this draws what it is given and routes a tap. A long press goes to the result's
 * own content type's add flow, whose questions the shared [EntryAddDialogs] draw.
 */
class EntryGlobalSearchScreen(
    val searchQuery: String = "",
    private val extensionFilter: String? = null,
    /**
     * The content type to search, when the caller already knows it: searching from a manga or a
     * novel searches that kind. Null opens on the Browse chip, which is what Browse itself wants.
     */
    val scopedContentType: ContentType? = null,
    /** The sources to cover for this search only, never remembered; null opens on the ones last chosen. */
    val sourceFilter: SearchSourceFilter? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val haptic = LocalHapticFeedback.current

        val mangaModel = assistedMetroViewModel<GlobalSearchViewModel, GlobalSearchViewModel.Factory> {
            create(initialExtensionFilter = extensionFilter)
        }
        val novelModel = metroViewModel<NovelGlobalSearchViewModel>()
        val providers = remember(mangaModel, novelModel) {
            listOf(MangaGlobalSearchProvider(mangaModel), NovelGlobalSearchProvider(novelModel))
        }
        val engine = assistedMetroViewModel<GlobalSearchEngine, GlobalSearchEngine.Factory> {
            // A deep link names one extension, so every source of it is in scope whether or not it is
            // pinned. Handed in for this search only, so it never replaces the filter you last chose.
            val filter = sourceFilter ?: SearchSourceFilter.All.takeUnless { extensionFilter.isNullOrEmpty() }
            create(providers, searchQuery, scopedContentType, filter)
        }
        val state by engine.state.collectAsStateWithLifecycle()
        val novelState by novelModel.state.collectAsStateWithLifecycle()

        val mangaBulk = metroViewModel<BulkFavoriteViewModel>()
        val novelBulk = metroViewModel<NovelBulkFavoriteViewModel>()
        val mangaBulkState by mangaBulk.state.collectAsStateWithLifecycle()
        val novelBulkState by novelBulk.state.collectAsStateWithLifecycle()
        // One selection spanning both halves, held as each type's own so the add verbs stay per-type.
        val selectionMode = mangaBulkState.selectionMode || novelBulkState.selectionMode
        val clearSelection = {
            mangaBulk.toggleSelectionMode(false)
            novelBulk.toggleSelectionMode(false)
        }
        BackHandler(enabled = selectionMode) { clearSelection() }

        // Decided when the batch is dispatched, not while the prompts run: the first one resolving
        // empties its own selection, and re-reading that would leave the second prompt unlabelled.
        var namePrompts by remember { mutableStateOf(false) }

        // A deep link naming one extension and matching one entry opens it rather than showing a
        // list of one.
        var showSingleLoadingScreen by remember {
            mutableStateOf(searchQuery.isNotEmpty() && !extensionFilter.isNullOrEmpty())
        }
        if (showSingleLoadingScreen) {
            LoadingScreen()
            // Waits on the search itself, never on the rows being non-empty: a filter naming an
            // extension that is not installed matches no source at all, and treating that as
            // "still loading" left this spinning with no way back but the system gesture.
            LaunchedEffect(state.rows, state.searched) {
                if (!state.searched) return@LaunchedEffect
                val only = state.rows.singleOrNull()?.state
                when {
                    only is EntrySearchState.Loading -> return@LaunchedEffect
                    only is EntrySearchState.Success ->
                        (only.entries.singleOrNull() as? Manga)
                            ?.let { navigator.replace(MangaScreen(it.id, true)) }
                            ?: run { showSingleLoadingScreen = false }
                    else -> showSingleLoadingScreen = false
                }
            }
            return
        }

        Scaffold(
            topBar = { _ ->
                GlobalSearchToolbar(
                    searchQuery = state.query,
                    progress = state.progress,
                    total = state.total,
                    navigateUp = navigator::pop,
                    onChangeSearchQuery = engine::updateQuery,
                    onSearch = engine::search,
                    hideSourceFilter = false,
                    sourceFilter = state.sourceFilter,
                    onChangeSearchFilter = engine::setSourceFilter,
                    onlyShowHasResults = state.onlyShowHasResults,
                    onToggleResults = engine::toggleHasResults,
                    onToggleSelectionMode = {
                        if (selectionMode) clearSelection() else mangaBulk.toggleSelectionMode(true)
                    },
                    selectionMode = selectionMode,
                    selectedCount = mangaBulkState.selection.size + novelBulkState.selection.size,
                    selectionTitle = selectionTitle(mangaBulkState.selection.size, novelBulkState.selection.size),
                    onClickClearSelection = clearSelection,
                    onSelectAll = {
                        val (manga, novels) = state.visibleRows.listedEntries()
                        manga.forEach { mangaBulk.select(it) }
                        novels.forEach(novelBulk::select)
                    },
                    onReverseSelection = {
                        val (manga, novels) = state.visibleRows.listedEntries()
                        mangaBulk.reverseSelection(manga)
                        novelBulk.reverseSelection(novels)
                    },
                    onChangeCategoryClick = {
                        namePrompts = mangaBulkState.selection.isNotEmpty() &&
                            novelBulkState.selection.isNotEmpty()
                        mangaBulk.addFavorite()
                        novelBulk.addFavorite()
                    },
                    tabs = {
                        ContentTypeTabs(
                            selected = state.contentType,
                            onSelect = engine::setContentType,
                        )
                    },
                )
            },
        ) { contentPadding ->
            when (state.emptyReason) {
                GlobalSearchEngine.EmptyReason.NoPinnedSources -> {
                    EmptyScreen(
                        stringRes = MR.strings.no_pinned_sources,
                        modifier = Modifier.padding(contentPadding),
                        actions = listOf(
                            EmptyScreenAction(MR.strings.all_sources, MaterialSymbols.Rounded.TravelExplore) {
                                engine.setSourceFilter(SearchSourceFilter.All)
                            },
                        ),
                    )
                    return@Scaffold
                }
                GlobalSearchEngine.EmptyReason.NoResults -> {
                    EmptyScreen(MR.strings.no_results_found, modifier = Modifier.padding(contentPadding))
                    return@Scaffold
                }
                null -> Unit
            }
            LazyColumn(contentPadding = contentPadding) {
                items(state.visibleRows.size, key = { state.visibleRows[it].key.toString() }) { index ->
                    SearchResultSection(
                        // Sections re-sort as each source lands, so they slide rather than jump.
                        modifier = Modifier.animateItem(),
                        row = state.visibleRows[index],
                        // Only on All, where the rows are interleaved and nothing else says which
                        // kind a source is. The Browse lists badge their rows on the same rule.
                        showContentType = state.contentType == ContentType.ALL,
                        showsFormat = state.showsFormat,
                        favoritedKeys = novelState.favoritedKeys,
                        mangaSelection = mangaBulkState.selection,
                        novelSelection = novelBulkState.selection,
                        getManga = { mangaModel.getManga(it) },
                        onClickSource = { row -> navigator.push(EntryCatalogueScreen(row.key, state.query)) },
                        onClickManga = { manga ->
                            if (selectionMode) {
                                mangaBulk.toggleSelection(
                                    manga,
                                )
                            } else {
                                navigator.push(MangaScreen(manga.id, true))
                            }
                        },
                        onLongClickManga = { manga ->
                            if (selectionMode) {
                                navigator.push(MangaScreen(manga.id, true))
                            } else {
                                mangaModel.addFlow.onLongClick(manga)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onClickNovel = { sourceId, item ->
                            if (selectionMode) {
                                novelBulk.toggleSelection(
                                    sourceId,
                                    item,
                                )
                            } else {
                                navigator.push(NovelScreen(sourceId, item.path, item.cover, fromSource = true))
                            }
                        },
                        onLongClickNovel = { sourceId, item ->
                            if (selectionMode) {
                                navigator.push(NovelScreen(sourceId, item.path, item.cover, fromSource = true))
                            } else {
                                novelModel.addFlow.onLongClick(item, sourceId)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                    )
                }
            }
        }

        EntryAddDialogs(mangaModel.addFlow)
        EntryAddDialogs(novelModel.addFlow)
        // One prompt at a time: resolving the manga one reveals the novel one, and each is named so
        // the second is not a surprise.
        BulkCategoryDialogs(mangaBulk, novelBulk, mangaBulkState.dialog, novelBulkState.dialog, namePrompts)
    }
}
