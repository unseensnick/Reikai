package yokai.presentation.settings.preferredsources

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.transitions.CrossfadeTransition
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController

/** Conductor host for [PreferredSourcesScreen], pushed from legacy Library settings. Mirrors
 *  [yokai.presentation.extension.repo.ExtensionRepoController]. */
class PreferredSourcesController : BaseComposeController() {

    @Composable
    override fun ScreenContent() {
        Navigator(
            screen = PreferredSourcesScreen(),
            content = { CrossfadeTransition(navigator = it) },
        )
    }
}
