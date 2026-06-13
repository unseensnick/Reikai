package reikai.presentation.novel.details

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.presentation.core.components.material.padding

/**
 * Page / volume selector for a paged light-novel source. Lists each page key; tapping one switches
 * the chapter list to that page (fetched lazily on first visit). A purely numeric key reads as
 * "Page N"; a label-grouped source's key (e.g. "Volume 3") shows verbatim. Uses the same
 * [AdaptiveSheet] as the chapter-settings and source sheets for a consistent feel.
 */
@Composable
internal fun NovelPageSelectorSheet(
    pages: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismiss) {
        LazyColumn {
            itemsIndexed(pages) { index, key ->
                val selected = index == selectedIndex
                Text(
                    text = key.toIntOrNull()?.let { "Page $it" } ?: key,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
                        .clickable { onSelect(index) }
                        .padding(horizontal = MaterialTheme.padding.large, vertical = MaterialTheme.padding.medium),
                )
            }
        }
    }
}
