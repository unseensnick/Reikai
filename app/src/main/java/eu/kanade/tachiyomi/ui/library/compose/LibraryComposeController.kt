package eu.kanade.tachiyomi.ui.library.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.compose.runtime.Composable
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.LibraryControllerBinding
import eu.kanade.tachiyomi.ui.base.controller.BaseCoroutineController
import eu.kanade.tachiyomi.ui.main.BottomSheetController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.fullAppBarHeight
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

        // The legacy controller wires this via scrollViewWith(recycler), which requires a
        // ScrollingView and so does not apply to ComposeView. Replicate the essential bit
        // manually: pad top by system inset + AppBar height so content sits below the legacy
        // toolbar, pad bottom by system inset + bottom nav height. Hooked into every layout
        // pass that could change those numbers, because the previous attempt that relied on
        // inset dispatch alone never fired with non-zero AppBar height.
        val syncPadding = {
            val rootInsets = view.rootWindowInsetsCompat
            val topSystem = rootInsets?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0
            val bottomSystem = rootInsets?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0
            val appBarHeight = fullAppBarHeight ?: activityBinding?.appBar?.height ?: 0
            val bottomNavHeight = activityBinding?.bottomNav?.height ?: 0
            binding.composeView.updatePadding(
                top = topSystem + appBarHeight,
                bottom = bottomSystem + bottomNavHeight,
            )
        }
        syncPadding()
        binding.composeView.doOnLayout { syncPadding() }
        activityBinding?.appBar?.doOnLayout { syncPadding() }
        activityBinding?.bottomNav?.doOnLayout { syncPadding() }

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
