package reikai.presentation.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/** Width of a global-search result card (shared by manga and novel). */
private val SearchCardWidth = 112.dp

/**
 * The horizontal row of result cards for one source in a global search, rendered through the shared
 * [EntryBrowseGridCell]. Generic over the item type [T], a browse result row or a migration candidate;
 * [toUi] is composable so a live row can be read as it changes. An empty list renders the shared "no
 * results" text (matches Mihon).
 */
@Composable
fun <T> EntrySearchCardRow(
    entries: List<T>,
    key: (T) -> Any,
    toUi: @Composable (T) -> EntryBrowseItemUi,
    onClick: (T) -> Unit,
    onLongClick: (T) -> Unit,
    isSelected: (T) -> Boolean,
) {
    if (entries.isEmpty()) {
        Text(
            text = stringResource(MR.strings.no_results_found),
            modifier = Modifier.padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            ),
        )
        return
    }
    LazyRow(
        contentPadding = PaddingValues(MaterialTheme.padding.small),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        // A source can list one entry twice in a page, and a repeated key crashes the row; browse
        // drops the repeat the same way (NovelPagingSource).
        items(items = entries.distinctBy(key), key = key) { entry ->
            Box(modifier = Modifier.width(SearchCardWidth)) {
                EntryBrowseGridCell(
                    ui = toUi(entry),
                    displayMode = LibraryDisplayMode.ComfortableGrid,
                    onClick = { onClick(entry) },
                    onLongClick = { onLongClick(entry) },
                    isSelected = isSelected(entry),
                )
            }
        }
    }
}
