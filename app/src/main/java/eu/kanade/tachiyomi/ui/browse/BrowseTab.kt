package eu.kanade.tachiyomi.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.result.ResultEffect
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionsViewModel
import eu.kanade.tachiyomi.ui.home.TopLevelRoute
import eu.kanade.tachiyomi.ui.main.MainActivity
import mihon.core.navigation.LocalNavigator
import reikai.presentation.browse.ReikaiBrowseViewModel
import reikai.presentation.browse.extension.reikaiExtensionsTab
import reikai.presentation.browse.feed.reikaiFeedTab
import reikai.presentation.browse.globalsearch.EntryGlobalSearchRoute
import reikai.presentation.browse.migrate.reikaiMigrateSourceTab
import reikai.presentation.browse.source.reikaiSourcesTab
import tachiyomi.i18n.MR

@Composable
fun BrowseTab() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current

    // RK: shared content-type filter, search query and LN update badge across the tabs. The
    //     search bar drives one list serving both content types, so its query is Browse-level
    //     rather than the manga model's.
    val browseViewModel = metroViewModel<ReikaiBrowseViewModel>()
    val searchQuery by browseViewModel.searchQuery.collectAsStateWithLifecycle()
    // Hoisted for extensions tab's search bar
    val extensionsViewModel = metroViewModel<ExtensionsViewModel>()

    // RK --> the Feed tab is opt-in, and can be asked to lead
    val showFeed by browseViewModel.showFeedTab.collectAsStateWithLifecycle()
    val feedFirst by browseViewModel.feedTabInFront.collectAsStateWithLifecycle()
    // Built only when it is shown. Resolving the model is what starts the feed loading, so the
    // call has to sit inside the condition rather than have its result filtered afterwards.
    val feedTab = if (showFeed) reikaiFeedTab() else null
    // RK <--

    val tabs = listOfNotNull(
        // RK: chip-switched manga + light-novel sources / extensions.
        feedTab.takeIf { feedFirst },
        reikaiSourcesTab(browseViewModel),
        feedTab.takeUnless { feedFirst },
        reikaiExtensionsTab(extensionsViewModel, browseViewModel),
        // RK: chip-switched manga + light-novel migrate-source list.
        reikaiMigrateSourceTab(browseViewModel),
    )

    val state = rememberPagerState { tabs.size }

    // RK: two tabs search the one Browse-level query, so Back closes it here rather than in each
    //     tab's content, which the pager does not compose while another tab shows.
    BackHandler(enabled = searchQuery != null && tabs[state.currentPage].searchEnabled) {
        browseViewModel.search(null)
    }

    TabbedScreen(
        titleRes = MR.strings.browse,
        tabs = tabs,
        state = state,
        // RK --> the Browse-level query, which the shared list serving both content types reads
        searchQuery = searchQuery,
        onChangeSearchQuery = browseViewModel::search,
        // RK <--
    )

    LaunchedEffect(Unit) {
        (context as? MainActivity)?.ready = true
    }

    // RK: derived, not the literal 1 upstream uses: the Feed tab can sit ahead of Extensions,
    //     and can be turned off again, so the index moves under this at runtime.
    val extensionsPage = tabs.indexOfFirst { it.titleRes == MR.strings.label_extensions }
    ResultEffect<BrowseShowExtensionsEvent> {
        state.scrollToPage(extensionsPage) // RK
    }

    ResultEffect<Unit>(resultKey = TopLevelRoute.Browse.reselectEventKey) {
        navigator.push(EntryGlobalSearchRoute()) // RK: the shared manga + novel global search
    }
}

data object BrowseShowExtensionsEvent
