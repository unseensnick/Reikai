package yokai.presentation.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.presentation.library.manga.MangaLibraryScreenModel

/**
 * Phase 1 single-tab manga library host. Phase 8 expands this into a tabbed shell with manga
 * and novel tabs sharing a common `LibraryTabContent` composable.
 *
 * Column count is fixed at adaptive 128.dp for Phase 1. `preferences.gridSize()` honoring lands
 * with the display-settings UI in Phase 3. The Phase 1 controller pads the ComposeView itself
 * so the LazyGrid sits below the legacy AppBar; the previous nested-scroll interop wiring was
 * removed because the legacy AppBarLayout in this controller's CoordinatorLayout intercepted
 * scroll deltas without consuming them, eating the grid's scroll events. AppBar collapse on
 * scroll lands in Phase 8 when the toolbar moves to Compose.
 */
class LibraryScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { MangaLibraryScreenModel() }
        val state by screenModel.state.collectAsState()
        val preferences: PreferencesHelper = Injekt.get()
        val libraryLayout = preferences.libraryLayout().get()

        val library = when (val s = state) {
            is LibraryTabState.Loading -> emptyMap()
            is LibraryTabState.Loaded -> s.library
        }

        LibraryContent(
            library = library,
            columns = 0,
            libraryLayout = libraryLayout,
        )
    }
}
