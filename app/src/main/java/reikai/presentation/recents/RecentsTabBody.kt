package reikai.presentation.recents

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.history.HistoryViewModel
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.flow.collectLatest
import reikai.presentation.history.NovelHistoryViewModel
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * The recents screen plus the two things every tab hosting it owes its host: telling the activity the
 * first feed has arrived, and getting the navigation bar out of the way of a selection. Shared because
 * three tabs render this screen and none of them differs here; what does differ (the badge, reselect)
 * stays on each tab, which builds it from the two helpers below where it has a read lane.
 */
@Composable
internal fun Screen.RecentsTabBody(
    engine: RecentsEngine,
    title: String,
    // Hoisted for a tab that speaks for itself: a reselect answering "no next chapter" comes from
    // outside the screen, and a host the screen made for itself would render it nowhere.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val context = LocalContext.current

    RecentsScreen(
        engine = engine,
        title = title,
        snackbarHostState = snackbarHostState,
    )

    val selectionEmpty = engine.selection.collectAsState().value.isEmpty()
    LaunchedEffect(selectionEmpty) {
        HomeScreen.showBottomNav(selectionEmpty)
    }

    val loaded = engine.rendered.collectAsStateWithLifecycle().value?.loading == false
    LaunchedEffect(loaded) {
        if (loaded) {
            (context as? MainActivity)?.ready = true
        }
    }
}

/**
 * The two history feeds' failed-write reports, for a tab that renders a read lane. The models are the
 * ones the tab's engine was built over, so these are the same instances, not a second pair.
 */
@Composable
internal fun HistoryFeedErrors(
    mangaHistory: HistoryViewModel,
    novelHistory: NovelHistoryViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val showInternalError: suspend () -> Unit = {
        snackbarHostState.showSnackbar(context.stringResource(MR.strings.internal_error))
    }
    LaunchedEffect(Unit) {
        mangaHistory.events.collectLatest { e ->
            when (e) {
                HistoryViewModel.Event.InternalError -> showInternalError()
            }
        }
    }
    LaunchedEffect(Unit) {
        novelHistory.events.collectLatest { e ->
            when (e) {
                NovelHistoryViewModel.Event.InternalError -> showInternalError()
            }
        }
    }
}

/** A tab's reselect: opens the newest read the chip shows, or says there is nothing to resume. */
internal suspend fun RecentsEngine.resumeLatestOrSay(context: Context, snackbarHostState: SnackbarHostState) {
    resumeLatest().launch(context) {
        snackbarHostState.showSnackbar(context.stringResource(MR.strings.no_next_chapter))
    }
}
