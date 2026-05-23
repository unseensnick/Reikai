package eu.kanade.tachiyomi.ui.library.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.compose.runtime.Composable
import androidx.core.view.isGone
import androidx.core.view.isVisible
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.LibraryControllerBinding
import eu.kanade.tachiyomi.ui.base.controller.BaseCoroutineController
import eu.kanade.tachiyomi.ui.main.BottomSheetController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import java.util.Locale
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.i18n.MR
import yokai.presentation.library.LibraryScreen
import yokai.presentation.theme.YokaiTheme
import yokai.util.lang.getString

class LibraryComposeController(
    bundle: Bundle? = null,
    val preferences: PreferencesHelper = Injekt.get(),
) : BaseCoroutineController<LibraryControllerBinding, LibraryComposePresenter>(bundle),
    BottomSheetController,
    RootSearchInterface,
    FloatingSearchInterface {

    override fun getTitle(): String? {
        return view?.context?.getString(MR.strings.library)
    }

    override fun getSearchTitle(): String? {
        val searchSuggestion by lazy { preferences.librarySearchSuggestion().get() }

        return searchTitle(
            if (preferences.showLibrarySearchSuggestions().get() && searchSuggestion.isNotBlank()) {
                "\"$searchSuggestion\""
            } else {
                view?.context?.getString(MR.strings.your_library)?.lowercase(Locale.ROOT)
            },
        )
    }

    override val presenter = LibraryComposePresenter()

    override fun createBinding(inflater: LayoutInflater) = LibraryControllerBinding.inflate(inflater)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        // library_controller.xml carries the proven CoordinatorLayout hosting that lets the
        // legacy app bar + bottom nav coexist with the Compose content. Hide the legacy
        // RecyclerView scaffolding and let the ComposeView drive the screen.
        binding.composeView.isVisible = true
        binding.swipeRefresh.isGone = true
        binding.fastScroller.isGone = true

        binding.composeView.setContent {
            YokaiTheme {
                ScreenContent()
            }
        }
    }

    @Composable
    fun ScreenContent() {
        Navigator(screen = LibraryScreen())
    }

    // BottomSheetController is required by the Conductor host's nav coordination. The Compose
    // path has no bottom sheets; settings sub-screens land via the local Voyager Navigator in
    // Phase 3 instead.
    override fun showSheet() {}
    override fun hideSheet() {}
    override fun toggleSheet() {}
}
