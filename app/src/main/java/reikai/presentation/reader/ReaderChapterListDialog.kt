package reikai.presentation.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.DeleteChaptersDialog
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.tachiyomi.data.download.model.Download
import reikai.domain.chapter.volumeRuns
import reikai.domain.download.swipeDownloadAction
import reikai.domain.download.whereDownloadOffered
import reikai.presentation.components.UndatedChapterDate
import reikai.presentation.components.chapterRowDate
import reikai.presentation.components.progressWhileUnread
import reikai.presentation.details.EntryChapterListItem
import reikai.presentation.details.VolumeHeader
import reikai.presentation.selection.EntrySelection
import reikai.presentation.selection.SelectionState
import reikai.presentation.selection.chapterSelectionOffers
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The reader's "view all chapters" sheet, both content types. Tap to jump, swipe to run the configured
 * chapter-swipe action, start, cancel or delete a download from the row, or long-press to select rows
 * for the details screen's bulk bar. Ported from Komikku for manga and grown into the shared one when
 * novels joined the same reader; Mihon has no such sheet.
 */
@Composable
fun ReaderChapterListDialog(
    onDismissRequest: () -> Unit,
    rows: List<ReaderChapterRow>,
    currentChapterId: Long,
    undatedChapterDate: UndatedChapterDate,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    onClickChapter: (Long) -> Unit,
    onMarkRead: (List<Long>, Boolean) -> Unit,
    onBookmark: (List<Long>, Boolean) -> Unit,
    onDownloadAction: (List<Long>, ChapterDownloadAction) -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        // The rows arrive asynchronously, and the list state below captures where to open scrolled to on
        // the composition that first builds it. Waiting here is what keeps that from being row zero.
        if (rows.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@AdaptiveSheet
        }
        // A local series split into volumes reads under a header per volume, as its details list does.
        val listed = remember(rows) { rows.underVolumeHeaders() }
        val orderedIds = remember(rows) { rows.map { it.id } }
        val listState = rememberLazyListState(
            listed.indexOfFirst { it is ReaderChapterRow && it.id == currentChapterId }.coerceAtLeast(0),
        )
        // Optimistic per-row overrides so a swipe lands immediately: every one of these writes is async,
        // and a deletion in particular is only visible once the cache catches up.
        val stateOverrides = remember { mutableStateMapOf<Long, Download.State>() }
        val readOverrides = remember { mutableStateMapOf<Long, Boolean>() }
        val bookmarkOverrides = remember { mutableStateMapOf<Long, Boolean>() }
        var selection by remember { mutableStateOf(SelectionState<Long>()) }
        var confirmingDelete by remember { mutableStateOf<List<Long>?>(null) }
        BackHandler(enabled = !selection.isEmpty) { selection = EntrySelection.clear() }

        // A row the queue is actually working on outranks an override, so a download started
        // elsewhere while the sheet is open is not masked by an earlier delete-swipe.
        fun downloadStateOf(row: ReaderChapterRow) =
            if (row.downloadState.isQueued) row.downloadState else stateOverrides[row.id] ?: row.downloadState
        fun runDownloadAction(chapterIds: List<Long>, action: ChapterDownloadAction) {
            chapterIds.forEach {
                when (action) {
                    ChapterDownloadAction.DELETE -> stateOverrides[it] = Download.State.NOT_DOWNLOADED
                    else -> stateOverrides.remove(it)
                }
            }
            onDownloadAction(chapterIds, action)
        }
        fun markRead(chapterIds: List<Long>, read: Boolean) {
            chapterIds.forEach { readOverrides[it] = read }
            onMarkRead(chapterIds, read)
        }
        fun bookmark(chapterIds: List<Long>, bookmarked: Boolean) {
            chapterIds.forEach { bookmarkOverrides[it] = bookmarked }
            onBookmark(chapterIds, bookmarked)
        }

        Column {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f, fill = false).heightIn(min = 200.dp, max = 500.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                items(
                    items = listed,
                    key = {
                        when (it) {
                            is ReaderChapterRow -> "reader-chapter-${it.id}"
                            else -> "reader-volume-${(it as EntryChapterListItem.Volume).key}"
                        }
                    },
                ) { item ->
                    if (item is EntryChapterListItem.Volume) {
                        VolumeHeader(
                            volume = item,
                            onSelect = { selection = EntrySelection.toggleBlock(selection, item.chapterIds) },
                            onMarkRead = { read -> markRead(item.chapterIds, read) },
                        )
                        return@items
                    }
                    val row = item as ReaderChapterRow
                    val downloadState = downloadStateOf(row)
                    val read = readOverrides[row.id] ?: row.read
                    val bookmark = bookmarkOverrides[row.id] ?: row.bookmark
                    MangaChapterListItem(
                        title = row.volume?.takeIf { listed.size > rows.size }?.titleOf(row.title) ?: row.title,
                        date = chapterRowDate(row.dateUpload, undatedChapterDate),
                        readProgress = progressWhileUnread(row.readProgress, read),
                        scanlator = row.subtitle,
                        read = read,
                        bookmark = bookmark,
                        selected = row.id in selection,
                        downloadIndicatorEnabled = true,
                        downloadIndicatorShown = row.offersDownload,
                        downloadStateProvider = { downloadState },
                        downloadProgressProvider = { row.downloadProgress },
                        chapterSwipeStartAction = chapterSwipeStartAction.whereDownloadOffered(row.offersDownload),
                        chapterSwipeEndAction = chapterSwipeEndAction.whereDownloadOffered(row.offersDownload),
                        onLongClick = { selection = EntrySelection.rangeOrToggle(selection, row.id, orderedIds) },
                        onClick = {
                            if (selection.isEmpty) {
                                onClickChapter(row.id)
                            } else {
                                selection = EntrySelection.toggle(selection, row.id)
                            }
                        },
                        onDownloadClick = { action -> runDownloadAction(listOf(row.id), action) },
                        onChapterSwipe = { action ->
                            when (action) {
                                LibraryPreferences.ChapterSwipeAction.ToggleRead -> markRead(listOf(row.id), !read)
                                LibraryPreferences.ChapterSwipeAction.ToggleBookmark ->
                                    bookmark(listOf(row.id), !bookmark)
                                LibraryPreferences.ChapterSwipeAction.Download ->
                                    runDownloadAction(listOf(row.id), downloadState.swipeDownloadAction())
                                LibraryPreferences.ChapterSwipeAction.Disabled -> {}
                            }
                        },
                    )
                }
            }
            val selected = rows.filter { it.id in selection }.map {
                it.copy(
                    read = readOverrides[it.id] ?: it.read,
                    bookmark = bookmarkOverrides[it.id] ?: it.bookmark,
                    downloadState = downloadStateOf(it),
                )
            }
            // The chapter being read is never deleted from under the reader, so it adds no Delete.
            val downloads = selected.map { row ->
                row.downloadState.takeIf {
                    row.offersDownload && !(row.id == currentChapterId && it == Download.State.DOWNLOADED)
                }
            }
            val offers = chapterSelectionOffers(selected, downloads)
            val ids = selected.map { it.id }
            fun act(verb: () -> Unit) {
                verb()
                selection = EntrySelection.clear()
            }
            MangaBottomActionMenu(
                visible = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onBookmarkClicked = { act { bookmark(ids, true) } }.takeIf { offers.bookmark },
                onRemoveBookmarkClicked = { act { bookmark(ids, false) } }.takeIf { offers.removeBookmark },
                onMarkAsReadClicked = { act { markRead(ids, true) } }.takeIf { offers.markRead },
                onMarkAsUnreadClicked = { act { markRead(ids, false) } }.takeIf { offers.markUnread },
                // The rows run in reading order, so the chapters before the picked one are the rows above it.
                onMarkPreviousAsReadClicked = {
                    act { markRead(orderedIds.takeWhile { it != ids.single() }, true) }
                }.takeIf { ids.size == 1 },
                onDownloadClicked = {
                    val fetch = ids.filterIndexed { i, _ -> downloads[i] !in setOf(null, Download.State.DOWNLOADED) }
                    act { runDownloadAction(fetch, ChapterDownloadAction.START) }
                }.takeIf { offers.download },
                onDeleteClicked = {
                    val doomed = ids.filterIndexed { i, _ -> downloads[i] == Download.State.DOWNLOADED }
                    act { confirmingDelete = doomed }
                }.takeIf { offers.delete },
            )
        }
        confirmingDelete?.let { doomed ->
            DeleteChaptersDialog(
                onDismissRequest = { confirmingDelete = null },
                onConfirm = { runDownloadAction(doomed, ChapterDownloadAction.DELETE) },
            )
        }
    }
}

/** Whether the queue is holding this chapter, which is the state a row must not let an override mask. */
private val Download.State.isQueued: Boolean
    get() = this == Download.State.QUEUE || this == Download.State.DOWNLOADING || this == Download.State.ERROR

/** [this] with a header before each volume, or as it is where volumes cannot head it ([volumeRuns]). */
private fun List<ReaderChapterRow>.underVolumeHeaders(): List<Any> {
    val runs = volumeRuns { it.volume?.key } ?: return this
    return runs.flatMap { (key, run) ->
        val volume = run.first().volume!!
        listOf(
            EntryChapterListItem.Volume(
                volume.series,
                key,
                volume.label,
                run.map {
                    it.id
                },
                run.count { it.read },
            ),
        ) +
            run
    }
}
