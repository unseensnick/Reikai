package eu.kanade.tachiyomi.ui.library.compose

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.ui.main.BottomSheetController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import eu.kanade.tachiyomi.util.view.isControllerVisible
import java.util.Locale
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.i18n.MR
import yokai.presentation.library.LibraryScreen
import yokai.util.lang.getString

class LibraryComposeController(
    bundle: Bundle? = null,
    val preferences: PreferencesHelper = Injekt.get(),
) : BaseComposeController(bundle),
    BottomSheetController,
    RootSearchInterface,
    FloatingSearchInterface {

    // Keep the legacy app bar visible so the existing "Library" title and search affordance
    // continue to work during Phase 1. Phase 8 swaps in a Compose-side toolbar with the
    // PrimaryTabRow under it, at which point this flips to true.
    override val shouldHideLegacyAppBar = false

    @Composable
    override fun ScreenContent() {
        Navigator(screen = LibraryScreen())
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        // BaseLegacyController.setTitle() isn't on the BaseComposeController path. Mirror the
        // relevant bits inline so the legacy app bar shows the right title and the search bar
        // gets a suggestion when we become visible.
        if (type.isEnter && isControllerVisible) {
            val ctx = view?.context ?: return
            (activity as? AppCompatActivity)?.title = ctx.getString(MR.strings.library)
            val mainActivity = activity as? MainActivity ?: return
            val searchSuggestion = preferences.librarySearchSuggestion().get()
            val suggested = if (
                preferences.showLibrarySearchSuggestions().get() && searchSuggestion.isNotBlank()
            ) {
                "\"$searchSuggestion\""
            } else {
                ctx.getString(MR.strings.your_library).lowercase(Locale.ROOT)
            }
            mainActivity.searchTitle = searchTitle(suggested)
        }
    }

    // BottomSheetController is required by the Conductor host's nav coordination. The Compose
    // path has no bottom sheets; settings sub-screens land via the local Voyager Navigator in
    // Phase 3 instead.
    override fun showSheet() {}
    override fun hideSheet() {}
    override fun toggleSheet() {}
}
