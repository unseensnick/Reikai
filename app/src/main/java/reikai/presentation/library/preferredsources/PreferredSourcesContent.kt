package reikai.presentation.library.preferredsources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Ranked-list UI for the preferred-source ordering: a guide blurb, the ordered ranking (up/down/
 * remove per row), then the remaining installed sources to add. Pure render over the screen state.
 */
@Composable
fun PreferredSourcesContent(
    preferred: List<PreferredSourceItem>,
    available: List<PreferredSourceItem>,
    contentPadding: PaddingValues,
    onMoveUp: (Long) -> Unit,
    onMoveDown: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onAdd: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth(), contentPadding = contentPadding) {
        item {
            Text(
                text = stringResource(MR.strings.pref_preferred_sources_guide),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }

        if (preferred.isEmpty()) {
            item {
                Text(
                    text = stringResource(MR.strings.pref_preferred_sources_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        } else {
            itemsIndexed(preferred, key = { _, item -> item.id }) { index, item ->
                SourceRow(item) {
                    IconButton(onClick = { onMoveUp(item.id) }, enabled = index > 0) {
                        Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null)
                    }
                    IconButton(onClick = { onMoveDown(item.id) }, enabled = index < preferred.lastIndex) {
                        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
                    }
                    IconButton(onClick = { onRemove(item.id) }) {
                        Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.strings.action_remove))
                    }
                }
            }
        }

        if (available.isNotEmpty()) {
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(MR.strings.pref_preferred_sources_add),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            items(available, key = { it.id }) { item ->
                SourceRow(item, modifier = Modifier.clickable { onAdd(item.id) }) {
                    IconButton(onClick = { onAdd(item.id) }) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(MR.strings.action_add))
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceRow(
    item: PreferredSourceItem,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.lang.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing()
    }
}
