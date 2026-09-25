package reikai.presentation.webview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import reikai.novel.source.NovelPageFetcher
import reikai.novel.source.NovelPageKind
import tachiyomi.core.common.util.lang.launchIO

/** What the in-app browser can do with a page of [novelId], for [rememberNovelPageActions]. */
@AssistedInject
class NovelPageActionsViewModel(
    @Assisted private val novelId: Long,
    fetcher: NovelPageFetcher,
    val runner: NovelPageActionRunner,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(novelId: Long): NovelPageActionsViewModel
    }

    /** What the novel's source takes a page for; empty until read, and when it takes none. */
    val kinds: StateFlow<Set<NovelPageKind>>
        field = MutableStateFlow(emptySet())

    init {
        viewModelScope.launchIO { kinds.value = fetcher.kinds(novelId) }
    }
}
