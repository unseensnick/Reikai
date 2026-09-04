package reikai.presentation.browse.feed

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.Sort
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.SelectAll
import reikai.domain.source.SourceKey
import reikai.novel.host.NovelItem
import reikai.presentation.browse.BulkCategoryDialogs
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.EntryAddDialogs
import reikai.presentation.browse.SearchResultSection
import reikai.presentation.browse.catalogue.EntryCatalogueScreen
import reikai.presentation.browse.components.BulkSelectionToolbar
import reikai.presentation.browse.globalsearch.EntrySearchState
import reikai.presentation.browse.selectionTitle
import reikai.presentation.novel.browse.NovelBulkFavoriteViewModel
import reikai.presentation.novel.browse.SelectedNovel
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Browse's Feed tab: a row per source the reader added, each showing what that source has right now.
 * One screen for both content types, over the same row fill the global search uses.
 */
@Composable
fun Screen.reikaiFeedTab(): TabContent {
    val model = metroViewModel<FeedViewModel>()
    val state by model.state.collectAsState()

    // Reordering is a mode over the same rows rather than a screen of its own, because the resolved
    // rows live on this model and a pushed screen gets its own store, so it would resolve them again.
    var reordering by rememberSaveable { mutableStateOf(false) }
    // Nothing left to arrange, so nothing to keep the mode open for. In an effect rather than inline:
    // writing state during composition that the same composition reads is what loops it.
    LaunchedEffect(state.entries.size) { if (state.entries.size < 2) reordering = false }

    // One selection spanning both halves, held as each type's own so the add verbs stay per-type.
    // Same shape the global search uses over the same rows.
    val mangaBulk = metroViewModel<BulkFavoriteViewModel>()
    val novelBulk = metroViewModel<NovelBulkFavoriteViewModel>()
    val mangaBulkState by mangaBulk.state.collectAsState()
    val novelBulkState by novelBulk.state.collectAsState()
    val selectionMode = mangaBulkState.selectionMode || novelBulkState.selectionMode
    val clearSelection = {
        mangaBulk.toggleSelectionMode(false)
        novelBulk.toggleSelectionMode(false)
    }
    // Decided when the batch is dispatched, not while the prompts run: the first one resolving
    // empties its own selection, and re-reading that would leave the second prompt unlabelled.
    var namePrompts by remember { mutableStateOf(false) }

    return TabContent(
        titleRes = MR.strings.label_feed,
        actionModeToolbar = if (!selectionMode) {
            null
        } else {
            {
                BulkSelectionToolbar(
                    selectedCount = mangaBulkState.selection.size + novelBulkState.selection.size,
                    title = selectionTitle(mangaBulkState.selection.size, novelBulkState.selection.size),
                    onClickClearSelection = clearSelection,
                    onChangeCategoryClick = {
                        namePrompts = mangaBulkState.selection.isNotEmpty() &&
                            novelBulkState.selection.isNotEmpty()
                        mangaBulk.addFavorite()
                        novelBulk.addFavorite(state.favoritedKeys)
                    },
                    onSelectAll = {
                        val (manga, novels) = state.listedEntries()
                        manga.forEach { mangaBulk.select(it) }
                        // Spelled out: NovelBulkFavoriteViewModel also has a (sourceId, item)
                        // select, so a callable reference here picks between overloads.
                        novels.forEach { novelBulk.select(it) }
                    },
                    onReverseSelection = {
                        val (manga, novels) = state.listedEntries()
                        mangaBulk.reverseSelection(manga)
                        novelBulk.reverseSelection(novels)
                    },
                )
            }
        },
        actions = if (reordering) {
            listOf(
                AppBar.Action(
                    title = stringResource(MR.strings.action_done_reordering),
                    icon = MaterialSymbols.Rounded.Close,
                    onClick = { reordering = false },
                ),
            )
        } else {
            listOfNotNull(
                AppBar.Action(
                    title = stringResource(MR.strings.action_add_to_feed),
                    icon = MaterialSymbols.Rounded.Add,
                    onClick = model::openAddDialog,
                ),
                AppBar.Action(
                    title = stringResource(MR.strings.action_reorder_feed),
                    icon = MaterialSymbols.AutoMirroredRounded.Sort,
                    onClick = { reordering = true },
                ).takeIf { state.entries.size > 1 },
                AppBar.Action(
                    title = stringResource(MR.strings.action_bulk_select),
                    icon = MaterialSymbols.Rounded.SelectAll,
                    onClick = { mangaBulk.toggleSelectionMode(true) },
                ).takeIf { state.entries.isNotEmpty() },
            )
        },
        content = { contentPadding, _ ->
            BackHandler(enabled = reordering || selectionMode) {
                if (selectionMode) clearSelection() else reordering = false
            }
            Crossfade(targetState = reordering, label = "feed_reorder") { showOrder ->
                if (showOrder) {
                    FeedOrderList(
                        entries = state.entries,
                        onReorder = model::reorder,
                        contentPadding = contentPadding,
                    )
                } else {
                    FeedContent(
                        state = state,
                        model = model,
                        contentPadding = contentPadding,
                        selectionMode = selectionMode,
                        mangaSelection = mangaBulkState.selection,
                        novelSelection = novelBulkState.selection,
                        onToggleManga = mangaBulk::toggleSelection,
                        onToggleNovel = { sourceId, item -> novelBulk.toggleSelection(sourceId, item) },
                    )
                }
            }
            BulkCategoryDialogs(
                mangaBulk,
                novelBulk,
                mangaBulkState.dialog,
                novelBulkState.dialog,
                namePrompts,
            )
        },
    )
}

/**
 * Every result the feed is currently showing, split back into the two halves each bulk model owns.
 * Unwrapped with filterIsInstance rather than a cast: the entries are typed Any, so a wrong cast
 * would compile and only fail once a source returned rows.
 */
internal fun FeedState.listedEntries(): Pair<List<Manga>, List<SelectedNovel>> {
    val manga = mutableListOf<Manga>()
    val novels = mutableListOf<SelectedNovel>()
    entries.forEach { entry ->
        val results = (entry.row.state as? EntrySearchState.Success)?.entries.orEmpty()
        when (val key = entry.row.key) {
            is SourceKey.Manga -> manga += results.filterIsInstance<Manga>()
            is SourceKey.Novel ->
                novels += results.filterIsInstance<NovelItem>().map { SelectedNovel(key.id, it) }
        }
    }
    return manga to novels
}

@Composable
private fun Screen.FeedContent(
    state: FeedState,
    model: FeedViewModel,
    contentPadding: PaddingValues,
    selectionMode: Boolean,
    mangaSelection: List<Manga>,
    novelSelection: List<SelectedNovel>,
    onToggleManga: (Manga) -> Unit,
    onToggleNovel: (String, NovelItem) -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow

    // Held by hand rather than derived from the rows: the rows only turn to loading a frame after the
    // gesture ends, by which time the indicator has already retracted. Cleared once they settle, with
    // a floor so a refetch the network answers instantly still shows something happened.
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(pulled) {
        if (!pulled) return@LaunchedEffect
        delay(400)
        model.state.first { feed -> feed.entries.none { it.row.state is EntrySearchState.Loading } }
        pulled = false
    }

    when {
        !state.loaded -> LoadingScreen(Modifier.padding(contentPadding))
        state.entries.isEmpty() -> EmptyScreen(
            stringRes = MR.strings.feed_empty,
            modifier = Modifier.padding(contentPadding),
        )
        else -> PullRefresh(
            refreshing = pulled,
            onRefresh = {
                pulled = true
                model.refresh()
            },
            enabled = true,
            // Without this the spinner draws behind the toolbar, where a pull looks like nothing.
            indicatorPadding = contentPadding,
        ) {
            LazyColumn(contentPadding = contentPadding) {
                items(state.entries.size, key = { state.entries[it].feedId }) { index ->
                    val entry = state.entries[index]
                    SearchResultSection(
                        row = entry.row,
                        // A saved-search row is titled by the search, so its source has to be said
                        // here or two rows on one source read as the same thing.
                        subtitle = entry.sourceName.takeIf { entry.savedSearch != null },
                        showContentType = true,
                        favoritedKeys = state.favoritedKeys,
                        mangaSelection = mangaSelection,
                        novelSelection = novelSelection,
                        getManga = { model.mangaState(it) },
                        onClickSource = {
                            navigator.push(
                                EntryCatalogueScreen(
                                    sourceKey = entry.row.key,
                                    // Where the row's own results came from, so opening it shows
                                    // more of the same rather than an unrelated listing.
                                    startLatest = entry.savedSearch == null && entry.supportsLatest,
                                    savedSearchId = entry.savedSearch?.id,
                                ),
                            )
                        },
                        // Removing a row mid-selection would take entries out from under it, so
                        // while selecting the heading does nothing.
                        onLongClickSource = { model.confirmRemove(entry) }.takeIf { !selectionMode },
                        // Both gestures invert while selecting, the way every other browse grid
                        // here does it: a tap picks, a long press previews.
                        onClickManga = { manga ->
                            if (selectionMode) onToggleManga(manga) else navigator.push(MangaScreen(manga.id, true))
                        },
                        onLongClickManga = { manga ->
                            if (selectionMode) {
                                navigator.push(MangaScreen(manga.id, true))
                            } else {
                                model.onLongPressManga(manga)
                            }
                        },
                        onClickNovel = { sourceId, item ->
                            if (selectionMode) {
                                onToggleNovel(sourceId, item)
                            } else {
                                navigator.push(NovelScreen(sourceId, item.path))
                            }
                        },
                        onLongClickNovel = { sourceId, item ->
                            if (selectionMode) {
                                navigator.push(NovelScreen(sourceId, item.path))
                            } else {
                                model.onLongPressNovel(item, sourceId)
                            }
                        },
                    )
                }
            }
        }
    }

    FeedDialogs(state.dialog, model)
    EntryAddDialogs(
        dialog = state.addDialog,
        // Whatever the raised entry was; only the migrate dialog reads it, and it is raised from a
        // duplicate of the same type as the row it came from.
        contentType = state.addDialogContentType,
        onDismissRequest = model::dismissAddDialog,
        onConfirmRemove = model::confirmRemove,
        onConfirmCategories = model::confirmCategories,
        onConfirmAddDuplicate = model::confirmAddDuplicate,
        onAddToGroup = model::addToGroup,
        onStartMigrate = model::startMigrate,
        onOpenEntryById = { id ->
            val novel = model.duplicateNovelRoute(id)
            if (novel == null) {
                navigator.push(MangaScreen(id))
            } else {
                navigator.push(NovelScreen(novel.first, novel.second))
            }
        },
    )
}

@Composable
private fun FeedDialogs(dialog: FeedDialog?, model: FeedViewModel) {
    when (dialog) {
        null -> Unit
        is FeedDialog.PickSource -> FeedSourcePickerDialog(
            sources = dialog.sources,
            onDismissRequest = model::dismissDialog,
            onPick = model::pickSource,
        )
        is FeedDialog.PickSearch -> FeedSearchPickerDialog(
            source = dialog.source,
            searches = dialog.searches,
            supportsLatest = dialog.supportsLatest,
            onDismissRequest = model::dismissDialog,
            onPick = { search -> model.add(dialog.source, search) },
        )
        is FeedDialog.Remove -> FeedRemoveDialog(
            name = dialog.entry.row.name,
            onDismissRequest = model::dismissDialog,
            onRemove = { model.remove(dialog.entry) },
        )
        FeedDialog.TooManyRows -> FeedFullDialog(onDismissRequest = model::dismissDialog)
    }
}
