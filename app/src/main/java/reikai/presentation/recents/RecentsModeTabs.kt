package reikai.presentation.recents

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import tachiyomi.presentation.core.components.material.TabText
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Which view of recent activity is on screen. Tabs rather than another chip row: this is navigation
 * between four views, where the chips below filter whichever one is showing, and giving both the same
 * control would say they do the same kind of thing. Drawn only where there is a choice to make, so
 * the two single-mode tabs never see it.
 */
@Composable
internal fun RecentsModeTabs(
    modes: List<RecentsMode>,
    selected: RecentsMode,
    onSelect: (RecentsMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryTabRow(
        selectedTabIndex = modes.indexOf(selected).coerceAtLeast(0),
        modifier = modifier,
    ) {
        modes.forEach { mode ->
            Tab(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                text = { TabText(text = stringResource(mode.labelRes)) },
                unselectedContentColor = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
