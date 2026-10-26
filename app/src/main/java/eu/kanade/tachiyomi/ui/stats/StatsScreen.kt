package eu.kanade.tachiyomi.ui.stats

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.stats.StatsScreenContent
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import reikai.presentation.stats.details.StatsGroupScreen
import reikai.presentation.stats.details.StatsMostReadScreen
import reikai.presentation.stats.details.StatsReadingScreen
import reikai.presentation.stats.details.StatsSeriesScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

class StatsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope() // RK

        val viewModel = metroViewModel<StatsViewModel>()
        val state by viewModel.state.collectAsState()
        // RK --> All / Manga / Novels switch
        val contentType by viewModel.contentType.collectAsState()
        // RK <--

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_stats),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            if (state is StatsScreenState.Loading) {
                LoadingScreen()
                return@Scaffold
            }

            StatsScreenContent(
                state = state as StatsScreenState.Success,
                paddingValues = paddingValues,
                // RK -->
                contentType = contentType,
                onContentTypeSelected = viewModel::setContentType,
                onSeeAll = { navigator.push(StatsGroupScreen(it)) },
                onOpenRow = { navigator.push(StatsSeriesScreen(it.group.key, it.name, contentType)) },
                onOpenKey = { navigator.push(StatsSeriesScreen(it, null, contentType)) },
                onOpenEntry = { scope.launch { viewModel.detailsScreen(it)?.let(navigator::push) } },
                onOpenReading = { navigator.push(StatsReadingScreen()) },
                onOpenMostRead = { navigator.push(StatsMostReadScreen()) },
                // RK <--
            )
        }
    }
}
