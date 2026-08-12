package reikai.presentation.recents

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import eu.kanade.tachiyomi.ui.updates.UpdatesViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import reikai.presentation.history.NovelHistoryViewModel
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Updates and History as one tab, with the four modes inside it. A third object rather than a reused
 * one because the bottom nav selects by tab class: a shared Tab would light two entries at once, and
 * the badge asks what kind of tab it is drawing.
 */
data object RecentsTab : Tab, ShowsUpdatesBadge {

    private val snackbarHostState = SnackbarHostState()

    private val reselectEvent = Channel<Unit>()

    /**
     * Buffered, so a launcher shortcut can name a mode before this tab has ever been composed: the
     * value waits for the first collector instead of being dropped, and a newer one replaces it.
     */
    private val showModeEvent = Channel<RecentsMode>(1, BufferOverflow.DROP_OLDEST)

    /** Opens this tab on [mode], for the two shortcuts that used to reach a tab of their own. */
    fun showMode(mode: RecentsMode) {
        showModeEvent.trySend(mode)
    }

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_updates_enter)
            return TabOptions(
                index = 1u,
                title = stringResource(MR.strings.label_recents),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        reselectEvent.send(Unit)
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val engine = rememberRecentsEngine()
        val mode by engine.mode.collectAsState()
        // Held for the badge reset and the two feeds' own error reports. The engine builds these same
        // instances out of this tab's store, so none of them is a second model.
        val updatesModel = viewModel<UpdatesViewModel>()
        val mangaHistory = viewModel<HistoryViewModel>()
        val novelHistory = viewModel<NovelHistoryViewModel>()

        RecentsTabBody(
            engine = engine,
            title = stringResource(MR.strings.label_recents),
            snackbarHostState = snackbarHostState,
        )

        // The badge counts what the updated lane shows, so a mode that does not draw that lane must
        // not clear a count the user has had no chance to look at.
        val showsUpdated = RecentsLaneKind.UPDATED in mode.lanes
        DisposableEffect(showsUpdated) {
            if (showsUpdated) updatesModel.resetNewUpdatesCount()

            onDispose {
                if (showsUpdated) updatesModel.resetNewUpdatesCount()
            }
        }

        LaunchedEffect(Unit) {
            mangaHistory.events.collectLatest { e ->
                when (e) {
                    HistoryViewModel.Event.InternalError ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                    // The screen announces a cleared history off its own dialog.
                    HistoryViewModel.Event.HistoryCleared -> Unit
                }
            }
        }

        LaunchedEffect(Unit) {
            novelHistory.events.collectLatest { e ->
                when (e) {
                    NovelHistoryViewModel.Event.InternalError ->
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
                    NovelHistoryViewModel.Event.HistoryCleared -> Unit
                }
            }
        }

        LaunchedEffect(Unit) {
            showModeEvent.receiveAsFlow().collectLatest(engine::setMode)
        }

        LaunchedEffect(Unit) {
            reselectEvent.receiveAsFlow().collectLatest {
                // Reselect resumes wherever there is reading to resume. Updates is the one mode with
                // no read lane, and it keeps the download-queue shortcut it has always had.
                if (RecentsLaneKind.READ in engine.mode.value.lanes) {
                    engine.resumeLatest().launch(context, navigator) {
                        snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
                    }
                } else {
                    navigator.push(DownloadQueueScreen)
                }
            }
        }
    }
}
