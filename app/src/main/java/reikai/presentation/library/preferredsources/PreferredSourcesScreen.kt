package reikai.presentation.library.preferredsources

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/** Settings sub-screen for ranking manga sources (pushed from Settings > Library). */
class PreferredSourcesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { PreferredSourcesScreenModel() }
        val state by screenModel.state.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_preferred_sources),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            when (val s = state) {
                PreferredSourcesScreenModel.State.Loading -> LoadingScreen()
                is PreferredSourcesScreenModel.State.Success -> PreferredSourcesContent(
                    preferred = s.preferred,
                    available = s.available,
                    contentPadding = paddingValues,
                    onMoveUp = screenModel::moveUp,
                    onMoveDown = screenModel::moveDown,
                    onRemove = screenModel::removeSource,
                    onAdd = screenModel::addSource,
                )
            }
        }
    }
}
