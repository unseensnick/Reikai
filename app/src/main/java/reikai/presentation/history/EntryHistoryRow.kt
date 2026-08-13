package reikai.presentation.history

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.presentation.manga.components.ChapterDownloadIndicator
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.data.download.model.Download
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

private val EntryHistoryRowHeight = 96.dp

/**
 * The neutral display data for one History row, so a single composable draws either content type.
 * The recents engine fills it from its own row projection, which is where the per-type divergences
 * (favorite-flag field, Date-against-Long read time, domain type) are already erased.
 */
data class EntryHistoryRowUi(
    // Coil model: a MangaCover or a NovelCover. MangaCover.Book accepts data: Any?.
    val cover: Any?,
    val title: String,
    val chapterNumber: Double,
    // Pre-formatted timestamp so the composable never sees the two read-time types.
    val readAt: String,
    val isFavorite: Boolean,
)

/**
 * One History row, shared by manga and novels. Cover opens details; the row resumes reading; long
 * press selects it; the chapter it names can be downloaded; the trash deletes the entry; a
 * not-yet-library entry also shows an add-to-library button. The shared twin of Mihon's `HistoryItem`,
 * which it replaced for both content types.
 *
 * The download control and the long press are the same ones a read row carries in the combined modes.
 * A row's capabilities follow the chapter it names, never the tab that happens to be drawing it.
 */
@Composable
fun EntryHistoryRow(
    ui: EntryHistoryRowUi,
    onClickCover: () -> Unit,
    onClickResume: () -> Unit,
    onClickDelete: () -> Unit,
    onClickFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
    downloadStateProvider: (() -> Download.State)? = null,
    downloadProgressProvider: () -> Int = { 0 },
    onDownloadClick: ((ChapterDownloadAction) -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .selectedBackground(selected)
            .combinedClickable(
                onClick = onClickResume,
                onLongClick = {
                    onLongClick()
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
            )
            .height(EntryHistoryRowHeight)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier.fillMaxHeight(),
            data = ui.cover,
            onClick = onClickCover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.small),
        ) {
            val textStyle = MaterialTheme.typography.bodyMedium
            Text(
                text = ui.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = textStyle,
            )
            Text(
                text = if (ui.chapterNumber > -1) {
                    stringResource(MR.strings.recent_manga_time, formatChapterNumber(ui.chapterNumber), ui.readAt)
                } else {
                    ui.readAt
                },
                modifier = Modifier.padding(top = 4.dp),
                style = textStyle,
            )
        }
        if (!ui.isFavorite) {
            IconButton(onClick = onClickFavorite) {
                Icon(
                    imageVector = Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(MR.strings.add_to_library),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (downloadStateProvider != null && onDownloadClick != null) {
            ChapterDownloadIndicator(
                enabled = true,
                modifier = Modifier.padding(start = 4.dp),
                downloadStateProvider = downloadStateProvider,
                downloadProgressProvider = downloadProgressProvider,
                onClick = onDownloadClick,
            )
        }
        IconButton(onClick = onClickDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(MR.strings.action_delete),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
