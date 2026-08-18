package eu.kanade.tachiyomi.ui.updates

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import reikai.presentation.recents.RecentsTabBody
import reikai.presentation.recents.ShowsUpdatesBadge
import reikai.presentation.recents.rememberUpdatesEngine
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

// RK: the badge marker, since which tab shows Updates is now a setting.
data object UpdatesTab : Tab, ShowsUpdatesBadge {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_updates_enter)
            return TabOptions(
                index = 1u,
                title = stringResource(MR.strings.label_recent_updates),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(DownloadQueueScreen)
    }

    @Composable
    override fun Content() {
        val engine = rememberUpdatesEngine()
        // RK: held only for the badge reset below. The engine resolves the same instance out of this
        //     tab's store, so this costs no second model.
        val viewModel = metroViewModel<UpdatesViewModel>()

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
    }
}
