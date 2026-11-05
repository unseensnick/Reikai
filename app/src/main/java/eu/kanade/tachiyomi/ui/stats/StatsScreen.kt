package eu.kanade.tachiyomi.ui.stats

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import reikai.presentation.stats.StatsContent

class StatsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        // RK: the body moved to StatsContent, shared with StatsTab, which has no way up
        StatsContent(navigateUp = navigator::pop)
    }
}
