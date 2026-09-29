package reikai.presentation.library.preferredsources

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import reikai.domain.library.ContentType
import reikai.domain.library.labelRes
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Settings sub-screen for ranking sources, split into Manga / Light novels tabs. Each tab ranks its
 * own content type; the rankings drive the trunk of a merged chapter list (manga + novel aggregators).
 */
class PreferredSourcesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = metroViewModel<PreferredSourcesViewModel>()
        val tabs = listOf(ContentType.MANGA to model.manga, ContentType.NOVELS to model.novels)

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_preferred_sources),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            val pagerState = rememberPagerState { tabs.size }
            val scope = rememberCoroutineScope()
            Column(modifier = Modifier.padding(top = paddingValues.calculateTopPadding())) {
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    tabs.forEachIndexed { index, (type, _) ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(stringResource(type.labelRes)) },
                        )
                    }
                }
                val panePadding = PaddingValues(bottom = paddingValues.calculateBottomPadding())
                HorizontalPager(modifier = Modifier.fillMaxSize(), state = pagerState) { page ->
                    RankingPane(editor = tabs[page].second, contentPadding = panePadding)
                }
            }
        }
    }
}

@Composable
private fun RankingPane(editor: SourceRankingEditor<*>, contentPadding: PaddingValues) {
    when (val state = editor.state.collectAsState().value) {
        PreferredSourcesState.Loading -> LoadingScreen()
        is PreferredSourcesState.Success -> PreferredSourcesContent(
            preferred = state.preferred,
            available = state.available,
            contentPadding = contentPadding,
            onMoveUp = editor::moveUp,
            onMoveDown = editor::moveDown,
            onRemove = editor::remove,
            onAdd = editor::add,
        )
    }
}
