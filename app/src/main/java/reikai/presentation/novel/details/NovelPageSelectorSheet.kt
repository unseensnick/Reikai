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
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Page / volume selector for a paged light-novel source. Lists each page key; tapping one switches
 * the chapter list to that page (fetched lazily on first visit). Each key is named by [novelPageText].
 * Uses the same [AdaptiveSheet] as the chapter-settings and source sheets for a consistent feel.
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
                    text = novelPageText(key),
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

/**
 * A page key as the picker and the page bar both name it: a numeric key is "Page N", any other key (a
 * label-grouped source's "Volume 3") shows as written. [pageCount] adds the "/ count" the bar shows.
 */
@Composable
internal fun novelPageText(key: String, pageCount: Int? = null): String {
    val number = key.toIntOrNull() ?: return key
    return if (pageCount == null) {
        stringResource(MR.strings.novel_chapter_list_page, number)
    } else {
        stringResource(MR.strings.novel_chapter_list_page_of, number, pageCount)
    }
}
