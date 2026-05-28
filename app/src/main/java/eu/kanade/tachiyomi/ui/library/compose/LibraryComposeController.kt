package eu.kanade.tachiyomi.ui.library.compose

import android.os.Bundle
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.ui.main.BottomSheetController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import yokai.presentation.library.LibraryScreen

class LibraryComposeController(bundle: Bundle? = null) :
    BaseComposeController(bundle),
    BottomSheetController,
    RootSearchInterface,
    FloatingSearchInterface {

    // The ComposeView spans the full screen (no bottom padding applied here). LibraryContent
    // owns the bottom-nav reservation via a reactive Modifier.padding(bottom = navReservedDp)
    // tied to the nav's translationY, so the LazyGrid renders into the freed space as the
    // user scrolls the nav off (instead of leaving a dead band below the list). Applying a
    // fixed nav.height padding here as well would double-reserve.

    @Composable
    override fun ScreenContent() {
        Navigator(screen = LibraryScreen())
    }

    // BottomSheetController is required by the Conductor host's nav coordination. The Compose
    // path has no bottom sheets; settings sub-screens land via the local Voyager Navigator in
    // Phase 3 instead.
    override fun showSheet() {}
    override fun hideSheet() {}
    override fun toggleSheet() {}
}
