package reikai.presentation.browse.globalsearch

import androidx.activity.compose.BackHandler
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
import eu.kanade.presentation.category.components.ChangeCategoryDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchViewModel
import eu.kanade.tachiyomi.ui.browse.source.globalsearch.SearchViewModel
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.novel.host.NovelItem
import reikai.presentation.browse.BulkCategoryDialogs
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.EntryBulkFavoriteViewModel
import reikai.presentation.browse.SearchResultSection
import reikai.presentation.browse.catalogue.EntryCatalogueScreen
import reikai.presentation.browse.components.EntryDuplicateDialog
import reikai.presentation.browse.components.EntryRemoveDialog
import reikai.presentation.browse.components.toDuplicateCard
import reikai.presentation.browse.selectionTitle
import reikai.presentation.components.ContentTypeTabs
import reikai.presentation.migrate.flow.EntryMigrateFor
import reikai.presentation.novel.browse.NovelBrowseDialog
import reikai.presentation.novel.browse.NovelBulkFavoriteViewModel
import reikai.presentation.novel.browse.SelectedNovel
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.globalsearch.NovelGlobalSearchViewModel
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * One cross-source search over both content types, with the content-type chip as a predicate over
 * the results rather than a switch between two screens.
 *
 * The query, which sources it covers, the order results land in and how many run at once live in
 * [GlobalSearchEngine]; this draws what it is given and routes a tap. Each content type keeps its own
 * long-press flow, because adding to the library differs all the way down.
 */
class EntryGlobalSearchScreen(
    val searchQuery: String = "",
    private val extensionFilter: String? = null,
    /**
     * The content type to search, when the caller already knows it: searching from a manga or a
     * novel searches that kind. Null opens on the Browse chip, which is what Browse itself wants.
     */
    private val scopedContentType: ContentType? = null,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val haptic = LocalHapticFeedback.current

        val mangaModel = assistedMetroViewModel<GlobalSearchViewModel, GlobalSearchViewModel.Factory> {
            create(initialQuery = searchQuery, initialExtensionFilter = extensionFilter)
        }
        val novelModel = metroViewModel<NovelGlobalSearchViewModel>()
        val providers = remember(mangaModel, novelModel) {
            listOf(MangaGlobalSearchProvider(mangaModel), NovelGlobalSearchProvider(novelModel))
        }
        val engine = assistedMetroViewModel<GlobalSearchEngine, GlobalSearchEngine.Factory> {
            create(providers, searchQuery, scopedContentType)
        }
        // A deep link names one extension, so every source of it is in scope whether or not it is
        // pinned; the chip is moved to match what is actually being searched.
        LaunchedEffect(Unit) {
            if (!extensionFilter.isNullOrEmpty()) engine.setSourceFilter(SearchSourceFilter.All)
        }
        val state by engine.state.collectAsStateWithLifecycle()
        val mangaState by mangaModel.state.collectAsStateWithLifecycle()
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

        // Every result currently listed, split back into the two halves each bulk model owns.
        // Unwrapped with filterIsInstance rather than a cast: the entries are typed Any, so a
        // wrong cast would compile and only fail once a source returned rows.
        fun listedEntries(): Pair<List<Manga>, List<SelectedNovel>> {
            val manga = mutableListOf<Manga>()
            val novels = mutableListOf<SelectedNovel>()
            state.visibleRows.forEach { row ->
                val entries = (row.state as? EntrySearchState.Success)?.entries.orEmpty()
                when (val key = row.key) {
                    is SourceKey.Manga -> manga += entries.filterIsInstance<Manga>()
                    is SourceKey.Novel ->
                        novels += entries.filterIsInstance<NovelItem>().map { SelectedNovel(key.id, it) }
                }
            }
            return manga to novels
        }
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
                        val (manga, novels) = listedEntries()
                        manga.forEach { mangaBulk.select(it) }
                        // Spelled out: NovelBulkFavoriteViewModel also has a (sourceId, item)
                        // select, so a callable reference here picks between overloads.
                        novels.forEach { novelBulk.select(it) }
                    },
                    onReverseSelection = {
                        val (manga, novels) = listedEntries()
                        mangaBulk.reverseSelection(manga)
                        novelBulk.reverseSelection(novels)
                    },
                    onChangeCategoryClick = {
                        namePrompts = mangaBulkState.selection.isNotEmpty() &&
                            novelBulkState.selection.isNotEmpty()
                        mangaBulk.addFavorite()
                        novelBulk.addFavorite(novelState.favoritedKeys)
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
            LazyColumn(contentPadding = contentPadding) {
                items(state.visibleRows.size, key = { state.visibleRows[it].key.toString() }) { index ->
                    SearchResultSection(
                        // Sections re-sort as each source lands, so they slide rather than jump.
                        modifier = Modifier.animateItem(),
                        row = state.visibleRows[index],
                        // Only on All, where the rows are interleaved and nothing else says which
                        // kind a source is. The Browse lists badge their rows on the same rule.
                        showContentType = state.contentType == ContentType.ALL,
                        favoritedKeys = novelState.favoritedKeys,
                        mangaSelection = mangaBulkState.selection,
                        novelSelection = novelBulkState.selection,
                        getManga = { mangaModel.getManga(it) },
                        onClickSource = { row ->
                            // The key carries the id, so routing never unwraps the opaque payload:
                            // the manga half holds the extension-facing Source, not the domain one.
                            when (val key = row.key) {
                                is SourceKey.Manga ->
                                    navigator.push(EntryCatalogueScreen(key, state.query))
                                is SourceKey.Novel ->
                                    navigator.push(EntryCatalogueScreen(key, state.query))
                            }
                        },
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
                                mangaModel.onLongClick(manga)
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
                                navigator.push(NovelScreen(sourceId, item.path))
                            }
                        },
                        onLongClickNovel = { sourceId, item ->
                            if (selectionMode) {
                                navigator.push(NovelScreen(sourceId, item.path))
                            } else {
                                novelModel.onLongClickItem(item, sourceId)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                    )
                }
            }
        }

        MangaLongPressDialogs(mangaModel, mangaState.dialog)
        NovelLongPressDialogs(novelModel, novelState.dialog)
        // One prompt at a time: resolving the manga one reveals the novel one, and each is named so
        // the second is not a surprise.
        BulkCategoryDialogs(mangaBulk, novelBulk, mangaBulkState.dialog, novelBulkState.dialog, namePrompts)
    }
}

@Composable
private fun Screen.MangaLongPressDialogs(model: GlobalSearchViewModel, dialog: SearchViewModel.Dialog?) {
    val navigator = LocalNavigator.currentOrThrow
    val onDismissRequest = model::clearDialog
    when (dialog) {
        is SearchViewModel.Dialog.AddDuplicateManga -> EntryDuplicateDialog(
            duplicates = dialog.duplicates,
            toUi = { it.toDuplicateCard(dialog.sourceLabels) },
            onDismissRequest = onDismissRequest,
            onConfirm = { model.addFavorite(dialog.manga) },
            onOpen = { navigator.push(MangaScreen(it.manga.id)) },
            onMigrate = { model.setMigrateDialog(it.manga.id, dialog.manga) },
            groupIdByEntryId = dialog.groupIdByMangaId,
            onAddToGroup = { selectedIds: List<Long> ->
                model.addToExistingGroup(dialog.manga, selectedIds)
            }.takeIf { dialog.suggestGroup },
        )
        is SearchViewModel.Dialog.Migrate -> EntryMigrateFor(
            contentType = ContentType.MANGA,
            currentId = dialog.current.id,
            targetId = dialog.target.id,
            onDismissRequest = onDismissRequest,
        )
        is SearchViewModel.Dialog.RemoveManga -> EntryRemoveDialog(
            title = dialog.manga.title,
            onDismissRequest = onDismissRequest,
            onConfirm = { model.changeMangaFavorite(dialog.manga) },
        )
        is SearchViewModel.Dialog.ChangeMangaCategory -> ChangeCategoryDialog(
            initialSelection = dialog.initialSelection,
            onDismissRequest = onDismissRequest,
            onEditCategories = { navigator.push(CategoryScreen()) },
            onConfirm = { include, _ ->
                model.confirmCategories(dialog.manga, include, dialog.alreadyFavorited)
            },
        )
        null -> {}
    }
}

@Composable
private fun Screen.NovelLongPressDialogs(model: NovelGlobalSearchViewModel, dialog: NovelBrowseDialog?) {
    val navigator = LocalNavigator.currentOrThrow
    when (dialog) {
        is NovelBrowseDialog.AddDuplicate -> EntryDuplicateDialog(
            duplicates = dialog.duplicates,
            toUi = { it.toDuplicateCard(dialog.sourceLabels, dialog.sourceSites) },
            onDismissRequest = model::dismissDialog,
            onConfirm = { model.addFromDuplicate(dialog.item, dialog.sourceId) },
            onOpen = { navigator.push(NovelScreen(it.novel.source, it.novel.url)) },
            onMigrate = { dup -> model.startMigrate(dup.novel.id, dialog.item, dialog.sourceId) },
            groupIdByEntryId = dialog.groupIdByNovelId,
            onAddToGroup = { selectedIds: List<Long> ->
                model.addToExistingGroup(dialog.item, dialog.sourceId, selectedIds)
            }.takeIf { dialog.suggestGroup },
        )
        is NovelBrowseDialog.ChangeCategory -> ChangeCategoryDialog(
            initialSelection = dialog.initialSelection,
            onDismissRequest = model::dismissDialog,
            onEditCategories = { navigator.push(CategoryScreen()) },
            onConfirm = { include, _ -> model.applyCategories(dialog.target, include) },
        )
        is NovelBrowseDialog.RemoveNovel -> EntryRemoveDialog(
            title = dialog.item.name,
            onDismissRequest = model::dismissDialog,
            onConfirm = { model.confirmRemove(dialog.item, dialog.sourceId) },
        )
        is NovelBrowseDialog.Migrate -> EntryMigrateFor(
            contentType = ContentType.NOVELS,
            currentId = dialog.currentId,
            targetId = dialog.targetId,
            onDismissRequest = model::dismissDialog,
        )
        null -> {}
    }
}
