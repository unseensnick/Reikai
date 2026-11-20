package eu.kanade.tachiyomi.ui.stats

import androidx.compose.runtime.Composable
import mihon.core.navigation.LocalNavigator
import reikai.presentation.stats.StatsContent

@Composable
fun StatsScreen() {
    val navigator = LocalNavigator.current

    // RK: the body moved to StatsContent, shared with StatsTab, which has no way up
    StatsContent(navigateUp = navigator::pop)
}
