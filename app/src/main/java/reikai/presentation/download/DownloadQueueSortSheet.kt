package reikai.presentation.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.i18n.stringResource

/** The key a download-queue sort orders by. */
enum class DownloadQueueSortKey { UPLOAD_DATE, CHAPTER_NUMBER }

/**
 * Sort modal for the download queue, built on the same [TabbedDialog] + [SortItem] as the library and
 * chapter sort sheets (rather than a nested overflow menu). The active key always shows its direction
 * arrow; tapping it flips the direction, tapping the other key switches to it. Stays open so the
 * direction can be toggled.
 */
@Composable
fun DownloadQueueSortSheet(
    sortKey: DownloadQueueSortKey,
    sortDescending: Boolean,
    onSort: (DownloadQueueSortKey) -> Unit,
    onDismissRequest: () -> Unit,
) {
    TabbedDialog(
        onDismissRequest = onDismissRequest,
        tabTitles = listOf(stringResource(MR.strings.action_sort)),
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .verticalScroll(rememberScrollState()),
        ) {
            SortItem(
                label = stringResource(MR.strings.action_order_by_chapter_number),
                sortDescending = sortDescending.takeIf { sortKey == DownloadQueueSortKey.CHAPTER_NUMBER },
                onClick = { onSort(DownloadQueueSortKey.CHAPTER_NUMBER) },
            )
            SortItem(
                label = stringResource(MR.strings.action_order_by_upload_date),
                sortDescending = sortDescending.takeIf { sortKey == DownloadQueueSortKey.UPLOAD_DATE },
                onClick = { onSort(DownloadQueueSortKey.UPLOAD_DATE) },
            )
        }
    }
}
