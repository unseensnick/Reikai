package reikai.presentation.stats

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.more.stats.StatsScreenContent
import eu.kanade.presentation.more.stats.StatsScreenState
import eu.kanade.tachiyomi.ui.stats.StatsViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Edit
import reikai.presentation.stats.details.StatsGroupScreen
import reikai.presentation.stats.details.StatsMostReadScreen
import reikai.presentation.stats.details.StatsReadingScreen
import reikai.presentation.stats.details.StatsSeriesScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/** The Statistics body, for the pushed StatsScreen and for StatsTab. */
@Composable
fun StatsContent(navigateUp: (() -> Unit)?) {
    val navigator = LocalNavigator.currentOrThrow
    val scope = rememberCoroutineScope()

    val viewModel = metroViewModel<StatsViewModel>()
    // As a tab the model lives all session, so it reloads whenever Statistics comes back on screen.
    StatsRefreshOnResume(viewModel::refresh)
    val state by viewModel.state.collectAsState()
    val contentType by viewModel.contentType.collectAsState()
    val cardLayout by viewModel.cardLayout.collectAsState()
    val hiddenCards by viewModel.hiddenCards.collectAsState()
    var isArranging by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = isArranging) { isArranging = false }

    Scaffold(
        topBar = { scrollBehavior ->
            if (isArranging) {
                AppBar(
                    title = stringResource(MR.strings.stats_arrange_cards),
                    navigateUp = { isArranging = false },
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.action_reset),
                                    onClick = viewModel::resetCardLayout,
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            } else {
                AppBar(
                    title = stringResource(MR.strings.label_stats),
                    navigateUp = navigateUp,
                    actions = {
                        AppBarActions(
                            persistentListOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.stats_arrange_cards),
                                    icon = MaterialSymbols.Rounded.Edit,
                                    onClick = { isArranging = true },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { paddingValues ->
        if (isArranging) {
            StatsArrangeList(
                layout = cardLayout,
                hidden = hiddenCards,
                contentPadding = paddingValues,
                onArrange = viewModel::setCardLayout,
                onHide = viewModel::setHiddenCards,
            )
            return@Scaffold
        }
        if (state is StatsScreenState.Loading) {
            LoadingScreen()
            return@Scaffold
        }

        StatsScreenContent(
            state = state as StatsScreenState.Success,
            paddingValues = paddingValues,
            contentType = contentType,
            onContentTypeSelected = viewModel::setContentType,
            onSeeAll = { navigator.push(StatsGroupScreen(it)) },
            onOpenRow = { navigator.push(StatsSeriesScreen(it.group.key, it.name, contentType)) },
            onOpenKey = { navigator.push(StatsSeriesScreen(it, null, contentType)) },
            onOpenEntry = { scope.launch { viewModel.detailsScreen(it)?.let(navigator::push) } },
            onOpenReading = { navigator.push(StatsReadingScreen()) },
            onOpenMostRead = { navigator.push(StatsMostReadScreen()) },
            cardLayout = cardLayout - hiddenCards,
        )
    }
}
