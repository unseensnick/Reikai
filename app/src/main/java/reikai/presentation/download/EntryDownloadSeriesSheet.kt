package reikai.presentation.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.rounded.Refresh
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * One series' queued chapters in download order, for both content types: status, page progress for
 * manga, the reason a chapter failed, and a cancel and a download-now per chapter. A bottom sheet on a
 * phone and a centred dialog on a tablet, as the reader's chapter list is.
 */
@Composable
fun EntryDownloadSeriesSheet(
    sheet: EntryDownloadQueueViewModel.SeriesSheet,
    onShowEntry: () -> Unit,
    onDownloadNow: (chapterId: Long) -> Unit,
    onCancel: (chapterId: Long) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = sheet.card.sourceName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = sheet.card.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onShowEntry) {
                    Text(text = stringResource(MR.strings.action_show_manga))
                }
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.heightIn(min = 200.dp, max = 500.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(sheet.chapters, key = { it.chapter.chapterId }) { row ->
                    EntryDownloadChapterRow(
                        row = row,
                        onDownloadNow = { onDownloadNow(row.chapter.chapterId) },
                        onCancel = { onCancel(row.chapter.chapterId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryDownloadChapterRow(
    row: EntryDownloadChapterUi,
    onDownloadNow: () -> Unit,
    onCancel: () -> Unit,
) {
    val chapter = row.chapter
    val failed = chapter.status == QueuedChapterStatus.ERROR
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            when (chapter.status) {
                QueuedChapterStatus.DOWNLOADING -> {
                    val progress = chapter.progress
                    val barModifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, end = 8.dp)
                    if (progress == null) {
                        LinearProgressIndicator(modifier = barModifier)
                    } else {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = barModifier)
                    }
                }
                QueuedChapterStatus.QUEUED -> Text(
                    text = stringResource(MR.strings.download_card_status_queued),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                QueuedChapterStatus.ERROR -> Text(
                    text = chapter.failure ?: stringResource(MR.strings.download_card_status_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDownloadNow) {
            Icon(
                imageVector = if (failed) MaterialSymbols.Rounded.Refresh else MaterialSymbols.Rounded.Download,
                contentDescription = stringResource(
                    if (failed) MR.strings.action_retry else MR.strings.action_start_downloading_now,
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onCancel) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Close,
                contentDescription = stringResource(MR.strings.action_cancel),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
