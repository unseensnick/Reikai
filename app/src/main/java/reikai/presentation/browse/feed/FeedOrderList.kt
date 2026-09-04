package reikai.presentation.browse.feed

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
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
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.DragHandle
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.presentation.core.components.material.padding

/**
 * The feed as a drag-to-reorder list: one compact row per feed row, titled the way the feed titles it.
 * A separate list rather than dragging the feed itself, because a feed row is a heading over a row of
 * covers and there is nothing to grab.
 */
@Composable
fun FeedOrderList(
    entries: List<FeedEntry>,
    onReorder: (orderedFeedIds: List<Long>) -> Unit,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    val items = remember(entries.map { it.feedId }) { entries.toMutableStateList() }
    var didDrag by remember { mutableStateOf(false) }

    val reorderState = rememberReorderableLazyListState(listState, contentPadding) { from, to ->
        val fromIndex = items.indexOfFirst { it.feedId == from.key }
        val toIndex = items.indexOfFirst { it.feedId == to.key }
        if (fromIndex == -1 || toIndex == -1) return@rememberReorderableLazyListState
        items.add(toIndex, items.removeAt(fromIndex))
        didDrag = true
    }

    // Once the drag settles, not on every frame it passes over: a write re-reads the table, and that
    // rebuild asks every source again. One refresh per reorder is the price; twenty would not be.
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && didDrag) {
            didDrag = false
            onReorder(items.map { it.feedId })
        }
    }

    LazyColumn(state = listState, contentPadding = contentPadding) {
        items(items.size, key = { items[it].feedId }) { index ->
            val entry = items[index]
            ReorderableItem(reorderState, entry.feedId) {
                FeedOrderRow(entry)
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.FeedOrderRow(entry: FeedEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MaterialSymbols.Rounded.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(end = MaterialTheme.padding.medium)
                .draggableHandle(),
        )
        Text(
            text = entry.row.name,
            style = MaterialTheme.typography.bodyLarge,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        // Only where the two differ, which is a saved-search row: its title is the search's name, so
        // without this two searches on one source read as unrelated rows.
        if (entry.savedSearch != null) {
            Text(
                text = entry.sourceName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
