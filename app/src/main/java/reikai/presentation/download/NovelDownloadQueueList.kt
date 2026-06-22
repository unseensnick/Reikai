package reikai.presentation.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import reikai.novel.download.NovelDownload
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Novel side of the unified download queue: one flat, drag-reorderable list. The novel title shows as
 * a caption only at a group boundary (its novel differs from the row above), so it reads grouped while
 * staying a single flat list the reorder library can drive. Drag to reorder, or cancel a row. The manga
 * side keeps its own Mihon RecyclerView; the [reikai.domain.library.ContentType] chip in
 * `DownloadQueueScreen` switches between them.
 */
@Composable
fun NovelDownloadQueueList(
    items: List<NovelDownloadQueueItem>,
    onReorder: (List<Long>) -> Unit,
    onCancel: (Long) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val localItems = remember { items.toMutableStateList() }
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        localItems.add(to.index, localItems.removeAt(from.index))
    }

    // Adopt the manager's order when not mid-drag (avoids clobbering the live drag); commit our order
    // once a drag settles, only if it actually changed.
    LaunchedEffect(items) {
        if (!reorderableState.isAnyItemDragging) {
            localItems.clear()
            localItems.addAll(items)
        }
    }
    LaunchedEffect(reorderableState.isAnyItemDragging) {
        if (!reorderableState.isAnyItemDragging) {
            val ids = localItems.map { it.chapterId }
            if (ids != items.map { it.chapterId }) onReorder(ids)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = contentPadding,
    ) {
        itemsIndexed(items = localItems, key = { _, item -> item.chapterId }) { index, item ->
            val showTitle = index == 0 || localItems[index - 1].novelId != item.novelId
            ReorderableItem(reorderableState, key = item.chapterId) {
                NovelDownloadQueueRow(
                    item = item,
                    showTitle = showTitle,
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.NovelDownloadQueueRow(
    item: NovelDownloadQueueItem,
    showTitle: Boolean,
    onCancel: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showTitle) {
            Text(
                text = item.novelTitle,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.DragHandle,
                contentDescription = null,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .draggableHandle(),
            )
            Text(
                text = item.chapterName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.state != NovelDownload.State.ERROR) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
            IconButton(onClick = { onCancel(item.chapterId) }) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = null)
            }
        }
    }
}
