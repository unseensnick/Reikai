package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.result.ResultEffect
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.more.MoreScreen
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.more.settings.screen.openSettings
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.home.TopLevelRoute
import exh.source.ExhPreferences
import exh.ui.batchadd.BatchAddRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import mihon.core.navigation.CategoryRoute
import mihon.core.navigation.DownloadQueueRoute
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.StatsRoute
import mihon.presentation.core.util.isTabletUi
import reikai.domain.stats.StatsShortcut
import reikai.novel.download.NovelDownloadManager
import reikai.presentation.download.EngineQueueStatus // RK
import reikai.presentation.download.downloadQueueState // RK
import tachiyomi.core.common.util.lang.launchIO

@Composable
fun MoreTab() {
    val navigator = LocalNavigator.current
    val isTabletUi = isTabletUi()
    val viewModel = metroViewModel<MoreViewModel>()
    val downloadQueueState by viewModel.downloadQueueState.collectAsState()
    MoreScreen(
        downloadQueueStateProvider = { downloadQueueState },
        downloadedOnly = viewModel.downloadedOnly,
        onDownloadedOnlyChange = { viewModel.downloadedOnly = it },
        incognitoMode = viewModel.incognitoMode,
        onIncognitoModeChange = { viewModel.incognitoMode = it },
        onClickDownloadQueue = { navigator.push(DownloadQueueRoute) },
        onClickCategories = { navigator.push(CategoryRoute) },
        onClickStats = { navigator.push(StatsRoute) },
        onClickDataAndStorage = {
            navigator.openSettings(SettingsDataScreen, isTabletUi)
        },
        // RK -->
        showBatchAdd = viewModel.isHentaiEnabled,
        onClickBatchAdd = { navigator.push(BatchAddRoute) },
        showStats = viewModel.showStats,
        // RK <--
        onClickSettings = { navigator.openSettings(twoPane = isTabletUi) },
        onClickAbout = { navigator.openSettings(AboutScreen, isTabletUi) }, // RK: About is its searchable screen
    )

    ResultEffect<Unit>(resultKey = TopLevelRoute.More.reselectEventKey) {
        navigator.openSettings(twoPane = isTabletUi)
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class)
class MoreViewModel(
    private val downloadManager: DownloadManager,
    preferences: BasePreferences,
    // RK -->
    exhPreferences: ExhPreferences,
    novelDownloadManager: () -> NovelDownloadManager,
    uiPreferences: UiPreferences,
    // RK <--
) : ViewModel() {

    var downloadedOnly by preferences.downloadedOnly.asState(viewModelScope)
    var incognitoMode by preferences.incognitoMode.asState(viewModelScope)

    // RK: drives the reactive batch-add entry in the More tab; flips with the adult-sources toggle.
    val isHentaiEnabled by exhPreferences.isHentaiEnabled().asState(viewModelScope)

    // RK: Statistics leaves the list while it is a tab of its own, one tap away in the bar.
    private val statsShortcut by uiPreferences.statsShortcut.asState(viewModelScope)
    val showStats: Boolean get() = statsShortcut != StatsShortcut.TAB

    private var _downloadQueueState: MutableStateFlow<DownloadQueueState> = MutableStateFlow(DownloadQueueState.Stopped)
    val downloadQueueState: StateFlow<DownloadQueueState> = _downloadQueueState.asStateFlow()

    init {
        // Handle running/paused status change and queue progress updating
        viewModelScope.launchIO {
            // RK --> both downloaders, through the queue-status rule the queue screen reads too
            val novels = novelDownloadManager()
            combine(
                downloadManager.isDownloaderRunning,
                downloadManager.queueState,
                novels.isDownloaderRunning,
                novels.queueState,
            ) { mangaRunning, mangaQueue, novelRunning, novelQueue ->
                downloadQueueState(
                    listOf(
                        EngineQueueStatus(mangaQueue.size, mangaRunning),
                        EngineQueueStatus(novelQueue.size, novelRunning),
                    ),
                )
            }
                .collectLatest { _downloadQueueState.value = it }
            // RK <--
        }
    }
}

sealed interface DownloadQueueState {
    data object Stopped : DownloadQueueState
    data class Paused(val pending: Int) : DownloadQueueState
    data class Downloading(val pending: Int) : DownloadQueueState
}
