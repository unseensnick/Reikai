package reikai.presentation.browse.feed

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import reikai.presentation.browse.EntryAddDialogs
import reikai.presentation.browse.SearchResultSection
import reikai.presentation.browse.catalogue.EntryCatalogueScreen
import reikai.presentation.browse.globalsearch.EntrySearchState
import reikai.presentation.novel.details.NovelScreen
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

    return TabContent(
        titleRes = MR.strings.label_feed,
        actions = listOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_add_to_feed),
                icon = Icons.Outlined.Add,
                onClick = model::openAddDialog,
            ),
        ),
        content = { contentPadding, _ ->
            FeedContent(state, model, contentPadding)
        },
    )
}

@Composable
private fun Screen.FeedContent(
    state: FeedState,
    model: FeedViewModel,
    contentPadding: PaddingValues,
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
                        mangaSelection = emptyList(),
                        novelSelection = emptyList(),
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
                        onLongClickSource = { model.confirmRemove(entry) },
                        onClickManga = { navigator.push(MangaScreen(it.id, true)) },
                        onLongClickManga = model::onLongPressManga,
                        onClickNovel = { sourceId, item ->
                            navigator.push(NovelScreen(sourceId, item.path))
                        },
                        onLongClickNovel = { sourceId, item ->
                            model.onLongPressNovel(item, sourceId)
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
