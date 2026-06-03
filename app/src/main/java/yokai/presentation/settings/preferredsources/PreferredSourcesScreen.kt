package yokai.presentation.settings.preferredsources

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.i18n.MR
import yokai.novel.host.LnPluginHost
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSourceManager
import yokai.presentation.AppBarType
import yokai.presentation.YokaiScaffold
import yokai.presentation.component.ReikaiPillTabRow
import yokai.util.Screen

/**
 * Global preferred-source ranking screen (Phase 6b, tabbed in Phase 8b). Two tabs, Manga and Light
 * novels, each an ordered ranking (tap-to-swap arrows) plus an add-remaining list, rendered by the
 * shared [PreferredSourcesList]. Each tab's aggregator ([yokai.domain.chapter.ChapterAggregation] /
 * [yokai.domain.novel.NovelChapterAggregation]) reads its ranking to pick the trunk of a merged
 * chapter list. Pure render over the two ScreenModels.
 *
 * Novel sources only populate [NovelSourceManager] after the plugin host loads them, so this screen
 * loads the host (which needs an Activity context) and hands installation off to the installer.
 */
class PreferredSourcesScreen : Screen() {

    @Composable
    override fun Content() {
        val onBackPress = LocalBackPress.currentOrThrow
        val mangaModel = rememberScreenModel { PreferredSourcesScreenModel() }
        val novelModel = rememberScreenModel { NovelPreferredSourcesScreenModel() }
        val mangaState by mangaModel.state.collectAsState()
        val novelState by novelModel.state.collectAsState()

        // Load installed novel plugins so the Light novels tab can list them. Mirrors NovelDetailsScreen:
        // the host needs a Context, so it lives here and is destroyed on dispose.
        val context = LocalContext.current
        val networkHelper = remember { Injekt.get<NetworkHelper>() }
        val installer = remember { Injekt.get<LnPluginInstaller>() }
        val host = remember { LnPluginHost(context, networkHelper.client) }
        DisposableEffect(host) { onDispose { host.destroy() } }
        LaunchedEffect(Unit) { runCatching { installer.loadInstalled(host) } }

        var selectedTab by rememberSaveable { mutableStateOf(0) }

        YokaiScaffold(
            onNavigationIconClicked = onBackPress,
            title = stringResource(MR.strings.pref_preferred_sources),
            appBarType = AppBarType.SMALL,
        ) { innerPadding ->
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                ReikaiPillTabRow(
                    selectedTabIndex = selectedTab,
                    tabs = listOf(stringResource(MR.strings.manga), stringResource(MR.strings.light_novels)),
                    onTabSelected = { selectedTab = it },
                )
                if (selectedTab == 0) {
                    (mangaState as? PreferredSourcesScreenModel.State.Success)?.let { s ->
                        PreferredSourcesList(
                            preferred = s.preferred,
                            available = s.available,
                            onMoveUp = mangaModel::moveUp,
                            onMoveDown = mangaModel::moveDown,
                            onRemove = mangaModel::removeSource,
                            onAdd = mangaModel::addSource,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    (novelState as? NovelPreferredSourcesScreenModel.State.Success)?.let { s ->
                        PreferredSourcesList(
                            preferred = s.preferred,
                            available = s.available,
                            onMoveUp = novelModel::moveUp,
                            onMoveDown = novelModel::moveDown,
                            onRemove = novelModel::removeSource,
                            onAdd = novelModel::addSource,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
