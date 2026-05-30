package yokai.presentation.library.updateError

import android.os.Bundle
import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.CrossfadeTransition
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController

/**
 * Conductor host for the Compose [LibraryUpdateErrorScreen]. The Compose library pushes this
 * controller via the Conductor router; it sets up the Voyager [Navigator] so the screen (and any
 * detail pushes it triggers) run on the Voyager stack. Mirrors
 * [yokai.presentation.details.manga.MangaDetailsComposeController]. Takes a [Bundle] so Conductor
 * can restore it after process death; the screen itself needs no arguments.
 */
class LibraryUpdateErrorComposeController(bundle: Bundle? = null) : BaseComposeController(bundle) {

    @Composable
    override fun ScreenContent() {
        Navigator(
            screen = LibraryUpdateErrorScreen(),
            content = { CrossfadeTransition(navigator = it) },
        )
    }
}
