package yokai.presentation.details

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import yokai.presentation.manga.components.MangaCover
import yokai.presentation.manga.components.MangaCoverRatio

/**
 * Plain, type-agnostic view data for one chapter row. Both a manga `Chapter` and a `NovelChapter`
 * map into this, so [DetailsContent] never sees a domain type.
 */
data class DetailsChapterRow(
    val id: Long,
    val name: String,
    val read: Boolean,
    val bookmark: Boolean,
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
    onToggleRead: (id: Long, read: Boolean) -> Unit,
    onToggleBookmark: (id: Long, bookmark: Boolean) -> Unit,
    modifier: Modifier = Modifier,
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
                onClick = { onChapterClick(chapter.id) },
                onToggleRead = { onToggleRead(chapter.id, !chapter.read) },
                onToggleBookmark = { onToggleBookmark(chapter.id, !chapter.bookmark) },
            )
        }
    }
}

@Composable
private fun DetailsChapterListRow(
    chapter: DetailsChapterRow,
    onClick: () -> Unit,
    onToggleRead: () -> Unit,
    onToggleBookmark: () -> Unit,
) {
    val contentColor = if (chapter.read) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 16.dp, vertical = 12.dp),
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
                modifier = Modifier.fillMaxWidth(),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (chapter.read) "Mark as unread" else "Mark as read") },
                onClick = { menuOpen = false; onToggleRead() },
            )
            DropdownMenuItem(
                text = { Text(if (chapter.bookmark) "Remove bookmark" else "Bookmark") },
                onClick = { menuOpen = false; onToggleBookmark() },
            )
        }
    }
}
