package eu.kanade.tachiyomi.ui.history

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.result.ResultEffect
import eu.kanade.tachiyomi.ui.home.TopLevelRoute
import reikai.domain.category.RecentsSurface
import reikai.presentation.recents.HistoryFeedErrors
import reikai.presentation.recents.RecentsTabBody
import reikai.presentation.recents.mangaHistoryModel
import reikai.presentation.recents.novelHistoryModel
import reikai.presentation.recents.rememberHistoryEngine
import reikai.presentation.recents.resumeLatestOrSay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun HistoryTab() {
    // RK: the navigator moved to the shared recents screen, see RecentsScreen
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val engine = rememberHistoryEngine()
    // RK: both models stay resolved for their event channels, which report a failed write and a
    //     cleared history. The engine builds these same two out of this tab's store.
    val viewModel = mangaHistoryModel(RecentsSurface.HISTORY)
    val novelViewModel = novelHistoryModel(RecentsSurface.HISTORY)

    RecentsTabBody(
        engine = engine,
        title = stringResource(MR.strings.history),
        snackbarHostState = snackbarHostState,
    )

    // RK: both feeds' failed-write reports, shared with the Recents tab.
    HistoryFeedErrors(viewModel, novelViewModel, snackbarHostState)

    ResultEffect<Unit>(resultKey = TopLevelRoute.History.reselectEventKey) {
        // RK: resume the newest read the chip is showing. The engine asks only the providers
        //     the chip selects, each through its own unfiltered query, so a search or a
        //     category filter still cannot move what resume opens.
        engine.resumeLatestOrSay(context, snackbarHostState)
    }
    // RK: upstream's openChapter helper is gone, the engine's resume target opens itself
}
