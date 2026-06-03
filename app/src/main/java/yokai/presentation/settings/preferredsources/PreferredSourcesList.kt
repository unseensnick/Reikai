package yokai.presentation.settings.preferredsources

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR

/**
 * Neutral preferred-source ranking list shared by the manga and novel tabs of
 * [PreferredSourcesScreen]. The [key] is a source id rendered as a String so both the manga
 * (Long id) and novel (String id) sides feed the same UI; each tab's ScreenModel maps the key
 * back to its native id type at its own boundary.
 */
@Immutable
data class PreferredSourceItem(val key: String, val name: String, val lang: String)

@Composable
fun PreferredSourcesList(
    preferred: List<PreferredSourceItem>,
    available: List<PreferredSourceItem>,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxWidth()) {
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
            itemsIndexed(preferred, key = { _, item -> item.key }) { index, item ->
                PreferredRow(
                    item = item,
                    canMoveUp = index > 0,
                    canMoveDown = index < preferred.lastIndex,
                    onMoveUp = { onMoveUp(item.key) },
                    onMoveDown = { onMoveDown(item.key) },
                    onRemove = { onRemove(item.key) },
                )
            }
        }

        if (available.isNotEmpty()) {
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SectionHeader(stringResource(MR.strings.pref_preferred_sources_add))
            }
            items(available, key = { it.key }) { item ->
                AvailableRow(item = item, onAdd = { onAdd(item.key) })
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun PreferredRow(
    item: PreferredSourceItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    SourceRow(item) {
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null)
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null)
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(MR.strings.remove))
        }
    }
}

@Composable
private fun AvailableRow(item: PreferredSourceItem, onAdd: () -> Unit) {
    SourceRow(item, modifier = Modifier.clickable(onClick = onAdd)) {
        IconButton(onClick = onAdd) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(MR.strings.add))
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
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
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
