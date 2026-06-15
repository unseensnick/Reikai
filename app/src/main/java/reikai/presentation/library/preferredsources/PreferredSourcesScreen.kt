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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
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
        val mangaModel = rememberScreenModel { PreferredSourcesScreenModel() }
        val novelModel = rememberScreenModel { NovelPreferredSourcesScreenModel() }
        val mangaState by mangaModel.state.collectAsState()
        val novelState by novelModel.state.collectAsState()

        val tabTitles = listOf(
            stringResource(MR.strings.content_type_manga),
            stringResource(MR.strings.content_type_novels),
        )

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_preferred_sources),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            val pagerState = rememberPagerState { tabTitles.size }
            val scope = rememberCoroutineScope()
            Column(modifier = Modifier.padding(top = paddingValues.calculateTopPadding())) {
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    tabTitles.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(title) },
                        )
                    }
                }
                val panePadding = PaddingValues(bottom = paddingValues.calculateBottomPadding())
                HorizontalPager(modifier = Modifier.fillMaxSize(), state = pagerState) { page ->
                    when (page) {
                        0 -> when (val s = mangaState) {
                            PreferredSourcesScreenModel.State.Loading -> LoadingScreen()
                            is PreferredSourcesScreenModel.State.Success -> PreferredSourcesContent(
                                preferred = s.preferred,
                                available = s.available,
                                contentPadding = panePadding,
                                onMoveUp = mangaModel::moveUp,
                                onMoveDown = mangaModel::moveDown,
                                onRemove = mangaModel::removeSource,
                                onAdd = mangaModel::addSource,
                            )
                        }
                        else -> when (val s = novelState) {
                            NovelPreferredSourcesScreenModel.State.Loading -> LoadingScreen()
                            is NovelPreferredSourcesScreenModel.State.Success -> PreferredSourcesContent(
                                preferred = s.preferred,
                                available = s.available,
                                contentPadding = panePadding,
                                onMoveUp = novelModel::moveUp,
                                onMoveDown = novelModel::moveDown,
                                onRemove = novelModel::removeSource,
                                onAdd = novelModel::addSource,
                            )
                        }
                    }
                }
            }
        }
    }
}
