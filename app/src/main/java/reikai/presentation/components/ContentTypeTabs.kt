package reikai.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import reikai.domain.library.ContentType
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource

/**
 * `All / Manga / Novels` as a tab strip rather than chips, for a screen that already spends its chip
 * row on filters. Global search does: keeping the content-type predicate a different kind of control
 * from the source filters beside it is what stops two chips both reading "All".
 *
 * The same tab row the Recents and Browse strips use, so it reads as one pattern.
 */
@Composable
fun ContentTypeTabs(
    selected: ContentType,
    onSelect: (ContentType) -> Unit,
    modifier: Modifier = Modifier,
    types: List<ContentType> = ContentType.entries,
) {
    PrimaryTabRow(
        selectedTabIndex = types.indexOf(selected).coerceAtLeast(0),
        modifier = modifier,
        // The tab strip and the filter row below it are one header block, so the rule that closes it
        // belongs under the whole block. Drawn by the caller, after the chips.
        divider = {},
    ) {
        types.forEach { type ->
            Tab(
                selected = selected == type,
                onClick = { onSelect(type) },
                text = { TabText(text = stringResource(type.labelRes)) },
                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
