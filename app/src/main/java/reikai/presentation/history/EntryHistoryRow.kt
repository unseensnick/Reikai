package reikai.presentation.history

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.util.lang.toTimestampString
import reikai.domain.novel.model.NovelHistoryWithRelations
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Date

private val EntryHistoryRowHeight = 96.dp

/**
 * The neutral display data for one History row. Both manga ([HistoryWithRelations]) and novels
 * ([NovelHistoryWithRelations]) map into this, so a single row composable draws either. This is the
 * unified-content-UI seam: the mappers below erase the per-type divergences (favorite-flag field,
 * [Date]-vs-[Long] read time, domain type) into plain data.
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

fun HistoryWithRelations.toEntryHistoryRowUi() = EntryHistoryRowUi(
    cover = coverData,
    title = title,
    chapterNumber = chapterNumber,
    readAt = readAt?.toTimestampString().orEmpty(),
    isFavorite = coverData.isMangaFavorite,
)

fun NovelHistoryWithRelations.toEntryHistoryRowUi() = EntryHistoryRowUi(
    cover = coverData,
    title = title,
    chapterNumber = chapterNumber,
    readAt = readAt?.let { Date(it).toTimestampString() }.orEmpty(),
    isFavorite = coverData.isNovelFavorite,
)

/**
 * One History row, shared by manga and novels. Cover opens details; the row resumes reading; the
 * trash icon deletes the entry; a not-yet-library entry also shows an add-to-library button. The
 * shared twin of Mihon's [eu.kanade.presentation.history.components.HistoryItem], which it replaces
 * for both content types.
 */
@Composable
fun EntryHistoryRow(
    ui: EntryHistoryRowUi,
    onClickCover: () -> Unit,
    onClickResume: () -> Unit,
    onClickDelete: () -> Unit,
    onClickFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clickable(onClick = onClickResume)
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
        IconButton(onClick = onClickDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(MR.strings.action_delete),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
