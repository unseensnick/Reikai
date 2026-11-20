package reikai.presentation.stats

import androidx.compose.runtime.Composable

/** Statistics in the bottom bar, when the Statistics shortcut setting asks for a tab. */
@Composable
fun StatsTab() {
    StatsContent(navigateUp = null)
}
