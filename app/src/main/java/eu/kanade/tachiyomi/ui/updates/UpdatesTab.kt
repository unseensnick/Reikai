package eu.kanade.tachiyomi.ui.updates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.navigation3.runtime.result.ResultEffect
import eu.kanade.tachiyomi.ui.home.TopLevelRoute
import mihon.core.navigation.DownloadQueueRoute
import mihon.core.navigation.LocalNavigator
import reikai.domain.category.RecentsSurface
import reikai.presentation.recents.RecentsTabBody
import reikai.presentation.recents.mangaUpdatesModel
import reikai.presentation.recents.rememberUpdatesEngine
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun UpdatesTab() {
    val navigator = LocalNavigator.current
    // RK --> the shared recents screen draws the tab, over the Updates engine
    val engine = rememberUpdatesEngine()
    // RK: held only for the badge reset below. The engine resolves the same instance out of this
    //     tab's store, so this costs no second model, and asks for it with the same surface.
    val viewModel = mangaUpdatesModel(RecentsSurface.UPDATES)

    RecentsTabBody(
        engine = engine,
        title = stringResource(MR.strings.label_recent_updates),
    )

    // The badge is the one host behaviour that is this tab's alone: it counts what the updated
    // lane shows, and no other tab draws that lane.
    DisposableEffect(Unit) {
        viewModel.resetNewUpdatesCount()

        onDispose {
            viewModel.resetNewUpdatesCount()
        }
    }
    // RK <--

    ResultEffect<Unit>(resultKey = TopLevelRoute.Updates.reselectEventKey) {
        navigator.push(DownloadQueueRoute)
    }
}
