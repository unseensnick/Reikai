package reikai.presentation.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

/** Status of a whole series' queued downloads, driving the card's chip + progress treatment. */
enum class EntryDownloadCardStatus { QUEUED, DOWNLOADING, ERROR }

/**
 * Neutral, content-agnostic model for one download-queue card. Manga and novels each aggregate their
 * queue by series into this, so a single composable renders either (the unified-content-UI seam). A
 * card is a whole series (novel / manga), not a single chapter: [downloadedChapters] / [totalChapters]
 * is the aggregate progress, and reorder / cancel act on the series ([seriesId]).
 */
data class EntryDownloadCardUi(
    val seriesId: Long,
    val sourceName: String,
    val title: String,
    val downloadedChapters: Int,
    val totalChapters: Int,
    val status: EntryDownloadCardStatus,
)

/**
 * The shared download-queue list for both content types: a flat, drag-reorderable list of per-series
 * cards. Each card can be dragged, bumped to the top / bottom, or cancelled. All three reorder paths
 * commit the same way, a new series-id order via [onReorder]; [onCancel] drops a whole series.
 *
 * A committed order (from a drag or a chevron) is held until the manager's queue echoes it back, so a
 * stale progress-driven emission arriving before the echo can't clobber it. Card content (counts,
 * status) always refreshes; only the ordering is guarded.
 */
@Composable
fun EntryDownloadCardList(
    items: List<EntryDownloadCardUi>,
    onReorder: (seriesIdsInOrder: List<Long>) -> Unit,
    onCancel: (seriesId: Long) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val localItems = remember { items.toMutableStateList() }
    val listState = rememberLazyListState()
    var didDrag by remember { mutableStateOf(false) }
    // The series order last committed here, held until the manager echoes it back (see class KDoc).
    var committedOrder by remember { mutableStateOf<List<Long>?>(null) }

    val reorderableState = rememberReorderableLazyListState(listState, contentPadding) { from, to ->
        val fromIndex = localItems.indexOfFirst { it.seriesId == from.key }
        val toIndex = localItems.indexOfFirst { it.seriesId == to.key }
        if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
        localItems.add(toIndex, localItems.removeAt(fromIndex))
        didDrag = true
    }

    // Commit a series order locally (keeping each card's fresh content) and up to the manager.
    fun commitOrder(order: List<Long>) {
        val bySeries = localItems.associateBy { it.seriesId }
        localItems.clear()
        localItems.addAll(order.mapNotNull { bySeries[it] })
        committedOrder = order
        onReorder(order)
    }

    LaunchedEffect(items) {
        if (reorderableState.isAnyItemDragging) return@LaunchedEffect
        val incomingById = items.associateBy { it.seriesId }
        val incomingIds = items.map { it.seriesId }
        val localIds = localItems.map { it.seriesId }
        val pending = committedOrder
        // Choose the order to display: adopt the manager's unless a committed order is still unechoed.
        val order = when {
            pending == null -> incomingIds
            pending.filter { it in incomingById } == incomingIds.filter { it in pending.toHashSet() } -> {
                committedOrder = null // echo caught up (agrees across shared series)
                incomingIds
            }
            incomingIds.toHashSet() == localIds.toHashSet() -> localIds // stale, same membership: keep
            else -> { // membership and order both changed: a genuine external change, resync
                committedOrder = null
                incomingIds
            }
        }
        // Rebuild in the chosen order with fresh card content; append any series not in the order.
        val seen = HashSet<Long>()
        val rebuilt = buildList {
            order.forEach { id -> incomingById[id]?.let { if (seen.add(id)) add(it) } }
            items.forEach { if (seen.add(it.seriesId)) add(it) }
        }
        if (rebuilt != localItems.toList()) {
            localItems.clear()
            localItems.addAll(rebuilt)
        }
    }
    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging && didDrag) {
            didDrag = false
            val order = localItems.map { it.seriesId }
            committedOrder = order
            onReorder(order)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        // Inset the cards from the screen edges (matching Tsundoku's 16.dp gutter + 8.dp top).
        contentPadding = contentPadding + PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(localItems, key = { it.seriesId }) { item ->
            ReorderableItem(reorderableState, key = item.seriesId) {
                EntryDownloadCard(
                    item = item,
                    onMoveToTop = {
                        commitOrder(
                            listOf(item.seriesId) + localItems.map {
                                it.seriesId
                            }.filter { it != item.seriesId },
                        )
                    },
                    onMoveToBottom = {
                        commitOrder(localItems.map { it.seriesId }.filter { it != item.seriesId } + item.seriesId)
                    },
                    onCancel = { onCancel(item.seriesId) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.EntryDownloadCard(
    item: EntryDownloadCardUi,
    onMoveToTop: () -> Unit,
    onMoveToBottom: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Outlined.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .draggableHandle(),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.sourceName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            MR.strings.download_card_chapters,
                            item.downloadedChapters,
                            item.totalChapters,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (item.status == EntryDownloadCardStatus.ERROR) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                EntryDownloadStatusChip(item.status)
            }
            if (item.status != EntryDownloadCardStatus.ERROR) {
                val fraction = if (item.totalChapters > 0) {
                    item.downloadedChapters.toFloat() / item.totalChapters
                } else {
                    0f
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 36.dp, top = 10.dp, end = 2.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                IconButton(onClick = onMoveToTop) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardDoubleArrowUp,
                        contentDescription = stringResource(MR.strings.action_move_to_top),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onMoveToBottom) {
                    Icon(
                        imageVector = Icons.Outlined.KeyboardDoubleArrowDown,
                        contentDescription = stringResource(MR.strings.action_move_to_bottom),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(MR.strings.action_cancel),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryDownloadStatusChip(status: EntryDownloadCardStatus) {
    val (label, color) = when (status) {
        EntryDownloadCardStatus.DOWNLOADING ->
            stringResource(MR.strings.download_card_status_downloading) to MaterialTheme.colorScheme.primary
        EntryDownloadCardStatus.ERROR ->
            stringResource(MR.strings.download_card_status_error) to MaterialTheme.colorScheme.error
        EntryDownloadCardStatus.QUEUED ->
            stringResource(MR.strings.download_card_status_queued) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = Modifier.padding(start = 8.dp, top = 2.dp),
    )
}
