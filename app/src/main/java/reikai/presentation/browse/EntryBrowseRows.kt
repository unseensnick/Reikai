package reikai.presentation.browse

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import exh.metadata.metadata.RaisedSearchMetadata
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import reikai.domain.novel.FavoritedNovels
import reikai.domain.source.SourceKey
import reikai.novel.host.NovelItem
import reikai.presentation.browse.catalogue.EntryBrowseRow
import reikai.presentation.browse.catalogue.EntryBrowseRowContent
import reikai.presentation.browse.catalogue.mapState
import reikai.presentation.novel.browse.NovelAddFlow
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.domain.manga.model.Manga

// The one result row every browse surface draws, built here for each content type: a catalogue page,
// a global search and the feed hold the same row, so a badge, a selection key or a tap cannot differ.

fun mangaRowKey(manga: Manga) = "manga:${manga.id}"

fun novelRowKey(sourceId: String, path: String) = "novel:$sourceId:$path"

/** A manga row over its live (manga, metadata) pair, which the adult-source gallery rows read. */
fun mangaBrowseRow(entry: StateFlow<Pair<Manga, RaisedSearchMetadata?>>): EntryBrowseRow = EntryBrowseRow(
    key = mangaRowKey(entry.value.first),
    content = entry.mapState { EntryBrowseRowContent(it.first.toEntryBrowseUi(), it) },
)

/**
 * A manga result that follows its stored row, so the badge and a long press read what is in the
 * library now rather than when the source answered. [stored] is subscribed only while a cell draws
 * the row: a search or feed row has no scope that ends when it scrolls away.
 */
fun liveMangaRow(listed: Manga, stored: Flow<Manga?>): EntryBrowseRow =
    mangaBrowseRow(
        ColdStateFlow(listed, stored.filterNotNull()).mapState<Manga, Pair<Manga, RaisedSearchMetadata?>> {
            it to null
        },
    )

/** A novel result, in the library while [favorited] holds its source and path. */
fun novelBrowseRow(item: NovelItem, sourceId: String, favorited: StateFlow<FavoritedNovels>): EntryBrowseRow =
    EntryBrowseRow(
        key = novelRowKey(sourceId, item.path),
        content = favorited.mapState { keys ->
            EntryBrowseRowContent(item.toEntryBrowseUi(keys.contains(sourceId, item.path), sourceId), item)
        },
    )

/** The manga behind a row built by [mangaBrowseRow]; sound only under a manga source key. */
val EntryBrowseRow.manga: Manga
    get() = (content.value.payload as Pair<*, *>).first as Manga

/** The result behind a row built by [novelBrowseRow]; sound only under a novel source key. */
val EntryBrowseRow.item: NovelItem
    get() = content.value.payload as NovelItem

/** The details page a result opens, as a listing opens it. */
fun EntryBrowseRow.detailsScreen(sourceKey: SourceKey): Screen = when (sourceKey) {
    is SourceKey.Manga -> MangaScreen(manga.id, fromSource = true)
    is SourceKey.Novel -> NovelScreen(sourceKey.id, item.path, item.cover, fromSource = true)
}

/** Starts the long-press add on this result of the source at [sourceKey], with its own type's flow. */
fun EntryBrowseRow.startAdd(sourceKey: SourceKey, mangaFlow: MangaAddFlow, novelFlow: NovelAddFlow) =
    when (sourceKey) {
        is SourceKey.Manga -> mangaFlow.onLongClick(manga)
        is SourceKey.Novel -> novelFlow.onLongClick(item, sourceKey.id)
    }

/** Holds the latest of [updates], starting at [initial], and follows them only while collected. */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ColdStateFlow<T>(initial: T, private val updates: Flow<T>) : StateFlow<T> {
    private val latest = MutableStateFlow(initial)
    override val value: T get() = latest.value
    override val replayCache: List<T> get() = latest.replayCache

    override suspend fun collect(collector: FlowCollector<T>): Nothing = coroutineScope {
        launch { updates.collect { latest.value = it } }
        latest.collect(collector)
    }
}
