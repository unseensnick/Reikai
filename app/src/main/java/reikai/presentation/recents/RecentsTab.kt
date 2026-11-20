package reikai.presentation.recents

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.result.ResultEffect
import eu.kanade.tachiyomi.ui.home.RecentsTabRoute
import mihon.core.navigation.DownloadQueueRoute
import mihon.core.navigation.LocalNavigator
import reikai.domain.category.RecentsSurface
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun RecentsTab() {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val engine = rememberRecentsEngine()
    val mode by engine.mode.collectAsState()
    // Held for the badge reset and the two feeds' own error reports. The engine builds these same
    // instances out of this tab's store, so none of them is a second model.
    val updatesModel = mangaUpdatesModel(RecentsSurface.RECENTS)
    val mangaHistory = mangaHistoryModel(RecentsSurface.RECENTS)
    val novelHistory = novelHistoryModel(RecentsSurface.RECENTS)

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

    HistoryFeedErrors(mangaHistory, novelHistory, snackbarHostState)

    ResultEffect<RecentsModeEvent> { engine.setMode(it.mode) }

    ResultEffect<Unit>(resultKey = RecentsTabRoute.reselectEventKey) {
        // Reselect resumes wherever there is reading to resume. Updates is the one mode with
        // no read lane, and it keeps the download-queue shortcut it has always had.
        if (RecentsLaneKind.READ in engine.mode.value.lanes) {
            engine.resumeLatestOrSay(context, snackbarHostState)
        } else {
            navigator.push(DownloadQueueRoute)
        }
    }
}

/**
 * Opens the combined tab on [mode], for the Updates and History shortcuts while it replaces both. The bus
 * buffers it, so a shortcut can name a mode before the tab has ever been composed.
 */
data class RecentsModeEvent(val mode: RecentsMode)
