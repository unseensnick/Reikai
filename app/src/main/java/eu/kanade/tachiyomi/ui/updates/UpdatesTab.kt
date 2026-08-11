package eu.kanade.tachiyomi.ui.updates

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.presentation.updates.UpdatesFilterDialog
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import reikai.presentation.recents.RecentsScreen
import reikai.presentation.recents.rememberUpdatesEngine
import reikai.presentation.updates.ReikaiUpdatesCategoryFilter
import reikai.presentation.updates.ReikaiUpdatesGroupToggle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

data object UpdatesTab : Tab {

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
        val context = LocalContext.current
        val engine = rememberUpdatesEngine()
        // RK: held only for the badge reset below. The engine resolves the same instance out of this
        //     tab's store, so this costs no second model.
        val viewModel = viewModel<UpdatesViewModel>()
        val settingsViewModel = viewModel<UpdatesSettingsViewModel>()
        var filterSheetOpen by rememberSaveable { mutableStateOf(false) }

        RecentsScreen(
            engine = engine,
            title = stringResource(MR.strings.label_recent_updates),
            onFilterClicked = { filterSheetOpen = true },
        )

        if (filterSheetOpen) {
            UpdatesFilterDialog(
                onDismissRequest = { filterSheetOpen = false },
                viewModel = settingsViewModel,
                reikaiCategoryRow = {
                    ReikaiUpdatesCategoryFilter(viewModel = settingsViewModel)
                },
                reikaiAfterFilters = {
                    ReikaiUpdatesGroupToggle(viewModel = settingsViewModel)
                },
            )
        }

        // The four behaviours below stay on the tab: each needs something the screen has no business
        // knowing about, the host activity or the navigation bar.
        val selectionEmpty = engine.selection.collectAsState().value.isEmpty()
        LaunchedEffect(selectionEmpty) {
            HomeScreen.showBottomNav(selectionEmpty)
        }

        val loaded = engine.assembled.collectAsState().value?.loading == false
        LaunchedEffect(loaded) {
            if (loaded) {
                (context as? MainActivity)?.ready = true
            }
        }

        DisposableEffect(Unit) {
            viewModel.resetNewUpdatesCount()

            onDispose {
                viewModel.resetNewUpdatesCount()
            }
        }
    }
}
