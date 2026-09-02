package reikai.presentation.browse.feed

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import reikai.presentation.browse.SearchResultSection
import reikai.presentation.browse.catalogue.EntryCatalogueScreen
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.i18n.MR
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
private fun FeedContent(
    state: FeedState,
    model: FeedViewModel,
    contentPadding: PaddingValues,
) {
    val navigator = LocalNavigator.currentOrThrow

    when {
        !state.loaded -> LoadingScreen(Modifier.padding(contentPadding))
        state.entries.isEmpty() -> EmptyScreen(
            stringRes = MR.strings.feed_empty,
            modifier = Modifier.padding(contentPadding),
        )
        else -> LazyColumn(contentPadding = contentPadding) {
            items(state.entries.size, key = { state.entries[it].feedId }) { index ->
                val entry = state.entries[index]
                SearchResultSection(
                    row = entry.row,
                    // A saved-search row is titled by the search, so its source has to be said here
                    // or two rows on one source read as the same thing.
                    subtitle = entry.sourceName.takeIf { entry.savedSearch != null },
                    showContentType = true,
                    favoritedKeys = state.favoritedKeys,
                    mangaSelection = emptyList(),
                    novelSelection = emptyList(),
                    getManga = { model.mangaState(it) },
                    onClickSource = { navigator.push(EntryCatalogueScreen(entry.row.key)) },
                    onLongClickSource = { model.confirmRemove(entry) },
                    onClickManga = { navigator.push(MangaScreen(it.id, true)) },
                    onLongClickManga = { navigator.push(MangaScreen(it.id, true)) },
                    onClickNovel = { sourceId, item -> navigator.push(NovelScreen(sourceId, item.path)) },
                    onLongClickNovel = { sourceId, item ->
                        navigator.push(NovelScreen(sourceId, item.path))
                    },
                )
            }
        }
    }

    FeedDialogs(state.dialog, model)
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
