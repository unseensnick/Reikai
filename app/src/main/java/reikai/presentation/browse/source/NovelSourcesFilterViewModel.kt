package reikai.presentation.browse.source

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import kotlin.time.Duration.Companion.seconds

/**
 * Backs the bulk novel source filter screen: lists every installed light-novel source grouped by
 * language, with a per-language switch toggling [ReikaiSourcePreferences.disabledNovelLanguages]
 * and per-source checkboxes toggling [ReikaiSourcePreferences.disabledNovelSources] (mirroring
 * Mihon's manga sources filter). A disabled source or language is hidden from the Sources tab and
 * global search, so this screen is where they are re-enabled.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelSourcesFilterViewModel(
    manager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val sourcePreferences: ReikaiSourcePreferences,
) : ViewModel() {

    val state: StateFlow<State> = combine(
        manager.sources,
        sourcePreferences.disabledNovelSources.changes(),
        sourcePreferences.disabledNovelLanguages.changes(),
    ) { sources, disabled, disabledLanguages ->
        State.Success(
            items = sources.groupBy { it.lang }
                .toSortedMap(LocaleHelper.comparator)
                .map { (lang, langSources) -> lang to langSources.sortedBy { it.name.lowercase() } },
            disabledSources = disabled,
            disabledLanguages = disabledLanguages,
        )
    }
        // The plugin host has to be loaded before the source list means anything, and this runs on
        // every (re)subscription now that the feed is not always-on. ensureLoaded is idempotent.
        .onStart { installer.ensureLoaded() }
        // ensureLoaded can build a QuickJS engine and read assets on the collecting dispatcher, which
        // without this is the main thread, so the sibling sources model carries the same line.
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State.Loading)

    fun toggleSource(sourceId: String) {
        val pref = sourcePreferences.disabledNovelSources
        val current = pref.get()
        pref.set(if (sourceId in current) current - sourceId else current + sourceId)
    }

    fun toggleLanguage(language: String) {
        val pref = sourcePreferences.disabledNovelLanguages
        val current = pref.get()
        pref.set(if (language in current) current - language else current + language)
    }

    sealed interface State {
        data object Loading : State

        @Immutable
        data class Success(
            val items: List<Pair<String, List<NovelSource>>>,
            val disabledSources: Set<String>,
            val disabledLanguages: Set<String>,
        ) : State {
            val isEmpty get() = items.isEmpty()
        }
    }
}
