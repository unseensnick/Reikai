package reikai.presentation.novel.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.relativeDateText
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.reader.toSwipeDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownload
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * In-reader "view all chapters" sheet for the novel reader, the novel twin of the manga reader's
 * [eu.kanade.presentation.reader.ChapterListDialog]. Reuses [MangaChapterListItem] so the rows (read
 * dot, date, bookmark, download button) match the novel details list and the manga reader exactly.
 * Tap to jump, swipe to bookmark, start/cancel/delete a download from the row. For a merged novel the
 * unified cross-source list shows a per-source label ([sourceNames]); single-source novels pass an
 * empty map, so no label appears. Swipe runs the configured chapter-swipe action (mark read/unread,
 * bookmark, or download, per direction), matching the details and manga-reader lists.
 */
@Composable
fun NovelReaderChapterListDialog(
    onDismissRequest: () -> Unit,
    chapters: List<NovelChapter>?,
    sourceNames: Map<Long, String>,
    currentChapterId: Long,
    downloadQueue: List<NovelDownload>,
    downloadedChapterIds: Set<Long>,
    onClickChapter: (NovelChapter) -> Unit,
    onBookmark: (NovelChapter, Boolean) -> Unit,
    onMarkRead: (NovelChapter, Boolean) -> Unit,
    chapterSwipeStartAction: LibraryPreferences.ChapterSwipeAction,
    chapterSwipeEndAction: LibraryPreferences.ChapterSwipeAction,
    onDownloadAction: (NovelChapter, ChapterDownloadAction) -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        if (chapters == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@AdaptiveSheet
        }
        val listState = rememberLazyListState(chapters.indexOfFirst { it.id == currentChapterId }.coerceAtLeast(0))
        // Optimistic per-chapter overrides so a swipe updates the row live (the snapshot list isn't observed
        // here): download because deleteChapters runs async and the row reads the disk snapshot, read/bookmark
        // because their writes are async too. Each swipe sets its override and passes the new target on.
        val stateOverrides = remember { mutableStateMapOf<Long, Download.State>() }
        val readOverrides = remember { mutableStateMapOf<Long, Boolean>() }
        val bookmarkOverrides = remember { mutableStateMapOf<Long, Boolean>() }
        fun runDownloadAction(chapter: NovelChapter, action: ChapterDownloadAction) {
            when (action) {
                ChapterDownloadAction.DELETE -> stateOverrides[chapter.id] = Download.State.NOT_DOWNLOADED
                else -> stateOverrides.remove(chapter.id)
            }
            onDownloadAction(chapter, action)
        }
        // Merged novels show a source label, which can make a row two lines: top-align so titles and
        // the download icon line up (matches the novel details list).
        val merged = sourceNames.isNotEmpty()
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(min = 200.dp, max = 500.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            items(items = chapters, key = { "novel-chapter-${it.id}" }) { chapter ->
                val active = downloadQueue.find { it.chapterId == chapter.id }
                val override = stateOverrides[chapter.id]
                val downloadState = when {
                    active != null -> active.state.toDownloadState()
                    override != null -> override
                    chapter.id in downloadedChapterIds -> Download.State.DOWNLOADED
                    else -> Download.State.NOT_DOWNLOADED
                }
                val read = readOverrides[chapter.id] ?: chapter.read
                val bookmark = bookmarkOverrides[chapter.id] ?: chapter.bookmark
                MangaChapterListItem(
                    title = chapter.name,
                    date = chapter.dateUpload.takeIf { it > 0L }?.let { relativeDateText(it) },
                    readProgress = (chapter.lastTextProgress / 100L).toInt()
                        .takeIf { !read && it > 0 }?.let { "$it%" },
                    scanlator = sourceNames[chapter.novelId],
                    read = read,
                    bookmark = bookmark,
                    selected = false,
                    downloadIndicatorEnabled = true,
                    downloadStateProvider = { downloadState },
                    downloadProgressProvider = { 0 },
                    chapterSwipeStartAction = chapterSwipeStartAction,
                    chapterSwipeEndAction = chapterSwipeEndAction,
                    onLongClick = {},
                    onClick = { onClickChapter(chapter) },
                    onDownloadClick = { action -> runDownloadAction(chapter, action) },
                    onChapterSwipe = { action ->
                        when (action) {
                            LibraryPreferences.ChapterSwipeAction.ToggleRead -> {
                                readOverrides[chapter.id] = !read
                                onMarkRead(chapter, !read)
                            }
                            LibraryPreferences.ChapterSwipeAction.ToggleBookmark -> {
                                bookmarkOverrides[chapter.id] = !bookmark
                                onBookmark(chapter, !bookmark)
                            }
                            LibraryPreferences.ChapterSwipeAction.Download ->
                                runDownloadAction(chapter, downloadState.toSwipeDownloadAction())
                            LibraryPreferences.ChapterSwipeAction.Disabled -> {}
                        }
                    },
                    verticalAlignment = if (merged) Alignment.Top else Alignment.CenterVertically,
                )
            }
        }
    }
}

private fun NovelDownload.State.toDownloadState(): Download.State = when (this) {
    NovelDownload.State.QUEUE -> Download.State.QUEUE
    NovelDownload.State.DOWNLOADING -> Download.State.DOWNLOADING
    NovelDownload.State.ERROR -> Download.State.ERROR
}
