package reikai.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.icerock.moko.resources.StringResource
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A tab strip heading a block whose chip row filters what the tabs pick: global search's All / Manga /
 * Novels above its source chips, and the recents views above their content-type chips. Tabs rather
 * than a second chip row, since the two do different kinds of thing and two chip rows would both
 * read "All".
 */
@Composable
fun <T> HeaderTabRow(
    items: List<T>,
    selected: T,
    label: (T) -> StringResource,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryTabRow(
        selectedTabIndex = items.indexOf(selected).coerceAtLeast(0),
        modifier = modifier,
        // The tab strip and the filter row below it are one header block, so the rule that closes it
        // belongs under the whole block. Drawn by the caller, after the chips.
        divider = {},
    ) {
        items.forEach { item ->
            Tab(
                selected = item == selected,
                onClick = { onSelect(item) },
                text = { TabText(text = stringResource(label(item))) },
                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
