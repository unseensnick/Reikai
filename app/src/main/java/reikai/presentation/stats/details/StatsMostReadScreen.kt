package reikai.presentation.stats.details

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import reikai.presentation.stats.StatsCoverTitleLine
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/** The overview's Most read card in full; tapping a series opens its details. */
class StatsMostReadScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<StatsMostReadViewModel>()
        val scope = rememberCoroutineScope()
        val titles by viewModel.titles.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.stats_most_read),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            val current = titles
            when {
                current == null -> LoadingScreen()
                current.isEmpty() -> EmptyScreen(
                    MR.strings.stats_nothing_read,
                    modifier = Modifier.padding(paddingValues),
                )
                else -> LazyColumn(contentPadding = paddingValues, horizontalAlignment = Alignment.CenterHorizontally) {
                    items(current, key = { it.entryId.toString() }) { title ->
                        StatsCoverTitleLine(
                            title = title.name,
                            readMs = title.readMs,
                            cover = title.cover,
                            modifier = Modifier.widthIn(
                                max = READING_MAX_WIDTH,
                            ).padding(horizontal = MaterialTheme.padding.medium),
                        ) {
                            scope.launch { viewModel.detailsScreen(title.entryId)?.let(navigator::push) }
                        }
                    }
                }
            }
        }
    }
}
