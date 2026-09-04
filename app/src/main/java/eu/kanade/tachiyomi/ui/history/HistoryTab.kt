package eu.kanade.tachiyomi.ui.history

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import reikai.presentation.history.NovelHistoryViewModel
import reikai.presentation.recents.RecentsTabBody
import reikai.presentation.recents.launch
import reikai.presentation.recents.rememberHistoryEngine
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object HistoryTab : Tab {

    private val snackbarHostState = SnackbarHostState()

    private val resumeLastChapterReadEvent = Channel<Unit>()

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_history_enter)
            return TabOptions(
                index = 2u,
                title = stringResource(MR.strings.label_recent_manga),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        resumeLastChapterReadEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val engine = rememberHistoryEngine()
        // RK: both models stay resolved for their event channels, which report a failed write and a
        //     cleared history. The engine builds these same two out of this tab's store.
        val viewModel = metroViewModel<HistoryViewModel>()
        val novelViewModel = metroViewModel<NovelHistoryViewModel>()

        RecentsTabBody(
            engine = engine,
            title = stringResource(MR.strings.history),
            snackbarHostState = snackbarHostState,
        )

        LaunchedEffect(Unit) {
            viewModel.events.collectLatest { e ->
                when (e) {
                    HistoryViewModel.Event.InternalError ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                }
            }
        }

        // RK --> novel history events
        LaunchedEffect(Unit) {
            novelViewModel.events.collectLatest { e ->
                when (e) {
                    NovelHistoryViewModel.Event.InternalError ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                }
            }
        }
        // RK <--

        LaunchedEffect(Unit) {
            resumeLastChapterReadEvent.receiveAsFlow().collectLatest {
                // RK: resume the newest read the chip is showing. The engine asks only the providers
                //     the chip selects, each through its own unfiltered query, so a search or a
                //     category filter still cannot move what resume opens.
                engine.resumeLatest().launch(context, navigator) {
                    snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                }
            }
        }
    }
}
