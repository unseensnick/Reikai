package reikai.presentation.recents

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.main.MainActivity

/**
 * The recents screen plus the two things every tab hosting it owes its host: telling the activity the
 * first feed has arrived, and getting the navigation bar out of the way of a selection. Shared because
 * three tabs render this screen and none of them differs here; what does differ (the badge, reselect)
 * stays on each tab.
 */
@Composable
internal fun Screen.RecentsTabBody(
    engine: RecentsEngine,
    title: String,
    // Hoisted for a tab that speaks for itself: a reselect answering "no next chapter" comes from
    // outside the screen, and a host the screen made for itself would render it nowhere.
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val context = LocalContext.current

    RecentsScreen(
        engine = engine,
        title = title,
        snackbarHostState = snackbarHostState,
    )

    val selectionEmpty = engine.selection.collectAsState().value.isEmpty()
    LaunchedEffect(selectionEmpty) {
        HomeScreen.showBottomNav(selectionEmpty)
    }

    val loaded = engine.rendered.collectAsState().value?.loading == false
    LaunchedEffect(loaded) {
        if (loaded) {
            (context as? MainActivity)?.ready = true
        }
    }
}
