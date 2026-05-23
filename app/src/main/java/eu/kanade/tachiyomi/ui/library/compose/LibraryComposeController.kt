package eu.kanade.tachiyomi.ui.library.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.core.view.doOnLayout
import androidx.core.view.updatePadding
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.ui.main.BottomSheetController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import eu.kanade.tachiyomi.util.view.activityBinding
import yokai.presentation.library.LibraryScreen

class LibraryComposeController(bundle: Bundle? = null) :
    BaseComposeController(bundle),
    BottomSheetController,
    RootSearchInterface,
    FloatingSearchInterface {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup, savedViewState: Bundle?): View {
        val composeView = super.onCreateView(inflater, container, savedViewState)
        // BaseComposeController hides the legacy app bar, so the top inset is handled by
        // Compose's WindowInsets.systemBars (Material3 Scaffold pulls them in by default).
        // The bottom nav is not a system inset; it is an app View that overlaps the bottom of
        // controller_container. Pad the ComposeView so the LazyGrid does not render under it.
        val applyBottomPadding = {
            composeView.updatePadding(bottom = activityBinding?.bottomNav?.height ?: 0)
        }
        applyBottomPadding()
        composeView.doOnLayout { applyBottomPadding() }
        activityBinding?.bottomNav?.doOnLayout { applyBottomPadding() }
        return composeView
    }

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
