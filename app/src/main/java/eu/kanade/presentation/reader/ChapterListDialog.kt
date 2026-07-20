package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.util.lang.toRelativeString
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reikai (R-feature): in-reader "view all chapters" sheet. Ported from Komikku, decoupled from its
 * reader-settings ScreenModel and E-Hentai date handling. Jump to a chapter on tap, swipe to run the
 * configured chapter-swipe action (mark read/unread, bookmark, or download, per direction, matching the
 * details list), and start/cancel/delete a download from the row.
 */
@Composable
fun ChapterListDialog(
    onDismissRequest: () -> Unit,
    chapters: ImmutableList<ReaderChapterItem>,
    onClickChapter: (Chapter) -> Unit,
    onBookmark: (Chapter, Boolean) -> Unit,
    onMarkRead: (Chapter, Boolean) -> Unit,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    onDownloadAction: (Chapter, ChapterDownloadAction) -> Unit,
    dateRelativeTime: Boolean,
) {
    val context = LocalContext.current
    val listState = rememberLazyListState(chapters.indexOfFirst { it.isCurrent }.coerceAtLeast(0))
    val downloadManager: DownloadManager = remember { Injekt.get() }
    val downloadQueueState by downloadManager.queueState.collectAsState()
    // Optimistic per-chapter overrides so a swipe updates the row live (the snapshot list isn't observed
    // here): download because deleteChapters runs async and the cache isn't watched, read/bookmark because
    // their writes are async too. Each swipe sets its override and passes the new target to the callback.
    val stateOverrides = remember { mutableStateMapOf<Long, Download.State>() }
    val readOverrides = remember { mutableStateMapOf<Long, Boolean>() }
    val bookmarkOverrides = remember { mutableStateMapOf<Long, Boolean>() }
    fun runDownloadAction(chapter: Chapter, action: ChapterDownloadAction) {
        when (action) {
            ChapterDownloadAction.DELETE -> stateOverrides[chapter.id] = Download.State.NOT_DOWNLOADED
            else -> stateOverrides.remove(chapter.id)
        }
        onDownloadAction(chapter, action)
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(min = 200.dp, max = 500.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            items(
                items = chapters,
                key = { "chapter-list-${it.chapter.id}" },
            ) { chapterItem ->
                val activeDownload = downloadQueueState.find { it.chapter.id == chapterItem.chapter.id }
                val progress = activeDownload?.let {
                    downloadManager.progressFlow()
                        .filter { it.chapter.id == chapterItem.chapter.id }
                        .map { it.progress }
                        .collectAsState(0).value
                } ?: 0
                val downloaded = if (chapterItem.manga.isLocal()) {
                    true
                } else {
                    downloadManager.isChapterDownloaded(
                        chapterItem.chapter.name,
                        chapterItem.chapter.scanlator,
                        chapterItem.chapter.url,
                        chapterItem.manga.title,
                        chapterItem.manga.source,
                    )
                }
                val override = stateOverrides[chapterItem.chapter.id]
                val downloadState = when {
                    activeDownload != null -> activeDownload.status
                    override != null -> override
                    downloaded -> Download.State.DOWNLOADED
                    else -> Download.State.NOT_DOWNLOADED
                }
                val read = readOverrides[chapterItem.chapter.id] ?: chapterItem.chapter.read
                val bookmark = bookmarkOverrides[chapterItem.chapter.id] ?: chapterItem.chapter.bookmark
                MangaChapterListItem(
                    title = chapterItem.chapter.name,
                    date = chapterItem.chapter.dateUpload
                        .takeIf { it > 0L }
                        ?.let {
                            LocalDate.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
                                .toRelativeString(context, dateRelativeTime, chapterItem.dateFormat)
                        },
                    readProgress = null,
                    // RK: in a merged group, lead the subtitle with the source name (then the
                    // scanlator) so each row shows which source it came from.
                    scanlator = listOfNotNull(chapterItem.sourceName, chapterItem.chapter.scanlator)
                        .joinToString(" • ")
                        .ifEmpty { null },
                    read = read,
                    bookmark = bookmark,
                    selected = false,
                    downloadIndicatorEnabled = true,
                    downloadStateProvider = { downloadState },
                    downloadProgressProvider = { progress },
                    chapterSwipeStartAction = chapterSwipeStartAction,
                    chapterSwipeEndAction = chapterSwipeEndAction,
                    onLongClick = {},
                    onClick = { onClickChapter(chapterItem.chapter) },
                    onDownloadClick = { action -> runDownloadAction(chapterItem.chapter, action) },
                    onChapterSwipe = { action ->
                        when (action) {
                            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                                readOverrides[chapterItem.chapter.id] = !read
                                onMarkRead(chapterItem.chapter, !read)
                            }
                            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                                bookmarkOverrides[chapterItem.chapter.id] = !bookmark
                                onBookmark(chapterItem.chapter, !bookmark)
                            }
                            LibraryPreferences.ChapterSwipeAction.Download ->
                                runDownloadAction(chapterItem.chapter, downloadState.toSwipeDownloadAction())
                            LibraryPreferences.ChapterSwipeAction.Disabled -> {}
                        }
                    },
                )
            }
        }
    }
}

/** Which download action a Download-configured swipe runs, given the row's current state; matches the
 *  details chapter list (start-now when absent, cancel while queued/downloading, delete when downloaded).
 *  Shared with the novel reader's chapter sheet. */
internal fun Download.State.toSwipeDownloadAction(): ChapterDownloadAction = when (this) {
    Download.State.ERROR, Download.State.NOT_DOWNLOADED -> ChapterDownloadAction.START_NOW
    Download.State.QUEUE, Download.State.DOWNLOADING -> ChapterDownloadAction.CANCEL
    Download.State.DOWNLOADED -> ChapterDownloadAction.DELETE
}
