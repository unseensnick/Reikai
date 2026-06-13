package reikai.presentation.download

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import reikai.novel.download.NovelDownload

/**
 * Novel side of the unified download queue: a flat per-novel grouped list with an indeterminate
 * spinner per active item (novel downloads are text-only, so there is no byte/page progress) and a
 * cancel affordance. The manga side keeps its own Mihon RecyclerView; the [reikai.domain.library.ContentType]
 * chip in `DownloadQueueScreen` switches between them.
 */
@Composable
fun NovelDownloadQueueList(
    groups: List<NovelDownloadQueueGroup>,
    onCancel: (Long) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = contentPadding,
    ) {
        groups.forEach { group ->
            item(key = "novel-${group.novelId}") {
                Text(
                    text = group.novelTitle,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(items = group.items, key = { "chapter-${it.chapterId}" }) { item ->
                NovelDownloadQueueRow(item = item, onCancel = onCancel)
            }
        }
    }
}

@Composable
private fun NovelDownloadQueueRow(item: NovelDownloadQueueItem, onCancel: (Long) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
