package reikai.presentation.updates

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.tachiyomi.data.download.model.Download
import reikai.presentation.recents.ChapterStateLine
import reikai.presentation.recents.ChapterSwipeBox
import reikai.presentation.recents.UpdatesRowShell
import tachiyomi.domain.library.service.LibraryPreferences.ChapterSwipeAction

/**
 * One flat (non-grouped) row in the Updates tab, shared by manga and novels. Draws the square cover,
 * title, and a chapter line (unread dot / bookmark / name / read-progress) plus the download
 * indicator. The per-type differences (title field, progress format, cover-tap target, download
 * providers) are supplied by the caller as plain data. Swiping runs Mihon's own details-row actions,
 * so one gesture definition serves a chapter row and a feed row. Replaces Mihon's `UpdatesUiItem` and
 * the novel `NovelUpdatesUiItem` for the flat leaf row; grouped children share `RecentsGroupChildRow`.
 */
@Composable
fun EntryUpdatesRow(
    cover: Any?,
    title: String,
    chapterName: String,
    read: Boolean,
    bookmark: Boolean,
    selected: Boolean,
    readProgress: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onClickCover: (() -> Unit)?,
    onDownloadChapter: ((ChapterDownloadAction) -> Unit)?,
    downloadStateProvider: () -> Download.State,
    downloadProgressProvider: () -> Int,
    chapterSwipeStartAction: ChapterSwipeAction,
    chapterSwipeEndAction: ChapterSwipeAction,
    onChapterSwipe: (ChapterSwipeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    ChapterSwipeBox(
        read = read,
        bookmark = bookmark,
        downloadState = downloadStateProvider(),
        startAction = chapterSwipeStartAction,
        endAction = chapterSwipeEndAction,
        onSwipe = onChapterSwipe,
    ) {
        UpdatesRowShell(
            cover = cover,
            title = title,
            dimmed = read,
            selected = selected,
            onClick = onClick,
            onLongClick = onLongClick,
            onClickCover = onClickCover,
            modifier = modifier,
            subtitle = {
                ChapterStateLine(
                    name = chapterName,
                    read = read,
                    bookmark = bookmark,
                    progress = readProgress,
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            trailing = {
                ChapterDownloadIndicator(
                    enabled = onDownloadChapter != null,
                    modifier = Modifier.padding(start = 4.dp),
                    downloadStateProvider = downloadStateProvider,
                    downloadProgressProvider = downloadProgressProvider,
                    onClick = { onDownloadChapter?.invoke(it) },
                )
            },
        )
    }
}
