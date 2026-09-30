package reikai.presentation.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SortItem
import tachiyomi.presentation.core.i18n.stringResource

/** The key a download-queue sort orders by. */
enum class DownloadQueueSortKey { UPLOAD_DATE, CHAPTER_NUMBER }

/** A sort the user applied to the download queue. */
data class DownloadQueueSort(val key: DownloadQueueSortKey, val descending: Boolean)

/**
 * The sort a tap on [tapped] applies: the key just applied flips its direction, any other key sorts
 * ascending. Null until the first tap, since the queue starts in enqueue order and no arrow is true.
 */
fun DownloadQueueSort?.next(tapped: DownloadQueueSortKey): DownloadQueueSort =
    if (this?.key == tapped) copy(descending = !descending) else DownloadQueueSort(tapped, descending = false)

/**
 * Sort modal for the download queue, built on the same [TabbedDialog] + [SortItem] as the library and
 * chapter sort sheets (rather than a nested overflow menu). Only the sort applied since the sheet was
 * opened shows an arrow, since a drag or a new enqueue can reorder the queue at any time, so the sheet
 * holds that sort itself and a reopened sheet starts with none. Stays open so the direction can be toggled.
 */
@Composable
fun DownloadQueueSortSheet(
    onSort: (DownloadQueueSort) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var sort by remember { mutableStateOf<DownloadQueueSort?>(null) }
    fun apply(key: DownloadQueueSortKey) {
        sort = sort.next(key).also(onSort)
    }
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
                sortDescending = sort?.takeIf { it.key == DownloadQueueSortKey.CHAPTER_NUMBER }?.descending,
                onClick = { apply(DownloadQueueSortKey.CHAPTER_NUMBER) },
            )
            SortItem(
                label = stringResource(MR.strings.action_order_by_upload_date),
                sortDescending = sort?.takeIf { it.key == DownloadQueueSortKey.UPLOAD_DATE }?.descending,
                onClick = { apply(DownloadQueueSortKey.UPLOAD_DATE) },
            )
        }
    }
}
