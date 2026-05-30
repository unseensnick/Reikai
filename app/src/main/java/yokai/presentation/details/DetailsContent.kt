package yokai.presentation.details

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.ArrowCircleDown
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import yokai.presentation.manga.components.MangaCover
import yokai.presentation.manga.components.MangaCoverRatio

/**
 * Per-chapter download state for the row indicator. Presentation-level so the shared body never
 * sees the `Download.State` model; the manga screen maps into this and novels leave it [NONE].
 */
enum class DetailsDownloadState { NONE, QUEUED, DOWNLOADING, DOWNLOADED, ERROR }

/**
 * Plain, type-agnostic view data for one chapter row. Both a manga `Chapter` and a `NovelChapter`
 * map into this, so [DetailsContent] never sees a domain type.
 */
data class DetailsChapterRow(
    val id: Long,
    val name: String,
    val read: Boolean,
    val bookmark: Boolean,
    val downloadState: DetailsDownloadState = DetailsDownloadState.NONE,
    /** 0..100, only meaningful while [downloadState] is [DetailsDownloadState.DOWNLOADING]. */
    val downloadProgress: Int = 0,
    val selected: Boolean = false,
)

/**
 * Shared details body for manga and (later) novels. Pure renderer over already-resolved header
 * fields plus a list of [DetailsChapterRow]; holds no domain types so both surfaces feed it.
 *
 * Chapter-row interactions (tap, long-press, download badge) and the manga-only feature rows
 * (downloads, tracking, merge) land in later phases as the screen grows.
 */
// no ScreenModel: pure UI, no async state.
@Composable
fun DetailsContent(
    coverData: Any?,
    title: String,
    author: String?,
    statusText: String?,
    description: String?,
    genres: List<String>,
    chapters: List<DetailsChapterRow>,
    onChapterClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** When non-null, each row shows a download indicator; tapping it calls back with the id. Null = no download UI (novels). */
    onDownloadClick: ((Long) -> Unit)? = null,
    /** True while at least one chapter is selected; rows toggle selection on tap instead of opening. */
    selectionActive: Boolean = false,
    /** When non-null, long-press (and, while [selectionActive], tap) toggles selection. Null = no multi-select (novels). */
    onToggleSelection: ((id: Long, selected: Boolean, fromLongPress: Boolean) -> Unit)? = null,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        // Leave room so the resume FAB doesn't cover the last chapter row.
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        item(key = "header") {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Box(modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)) {
                    MangaCover(
                        data = coverData,
                        ratio = MangaCoverRatio.BOOK,
                        contentDescription = title,
                        modifier = Modifier.width(180.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                author?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                statusText?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(12.dp))
                    SelectionContainer { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                }
                genres.takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it.joinToString("  •  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "chapter_count") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text(
                    text = "${chapters.size} chapter${if (chapters.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }
        }
        items(items = chapters, key = { it.id }) { chapter ->
            DetailsChapterListRow(
                chapter = chapter,
                selectionActive = selectionActive,
                onClick = {
                    if (selectionActive && onToggleSelection != null) {
                        onToggleSelection(chapter.id, !chapter.selected, false)
                    } else {
                        onChapterClick(chapter.id)
                    }
                },
                onLongClick = onToggleSelection?.let { cb -> { cb(chapter.id, true, true) } },
                onDownloadClick = onDownloadClick?.let { cb -> { cb(chapter.id) } },
            )
        }
    }
}

@Composable
private fun DetailsChapterListRow(
    chapter: DetailsChapterRow,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onDownloadClick: (() -> Unit)?,
) {
    val contentColor = if (chapter.read) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val rowBackground = if (chapter.selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (chapter.bookmark) {
            Icon(
                imageVector = Icons.Filled.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(16.dp),
            )
        }
        Text(
            text = chapter.name,
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        when {
            selectionActive -> Icon(
                imageVector = if (chapter.selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (chapter.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            onDownloadClick != null -> ChapterDownloadIndicator(
                state = chapter.downloadState,
                progress = chapter.downloadProgress,
                onClick = onDownloadClick,
            )
        }
    }
}

@Composable
private fun ChapterDownloadIndicator(
    state: DetailsDownloadState,
    progress: Int,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        when (state) {
            DetailsDownloadState.NONE -> Icon(
                imageVector = Icons.Outlined.ArrowCircleDown,
                contentDescription = "Download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            DetailsDownloadState.QUEUED -> CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            DetailsDownloadState.DOWNLOADING -> CircularProgressIndicator(
                progress = { progress / 100f },
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            DetailsDownloadState.DOWNLOADED -> Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Delete download",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            DetailsDownloadState.ERROR -> Icon(
                imageVector = Icons.Filled.Error,
                contentDescription = "Retry download",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}
