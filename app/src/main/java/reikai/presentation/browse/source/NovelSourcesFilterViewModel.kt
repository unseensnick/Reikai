package reikai.presentation.browse.source

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.extension.ExtensionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.ToggleNovelSource
import reikai.novel.registry.LnRepoRegistries
import reikai.novel.registry.listedPlugins
import reikai.novel.source.NovelExtensionFormat
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.novel.source.toLangCode
import reikai.presentation.browse.sourcesFilterLanguageOrder
import reikai.presentation.browse.sourcesFilterSourceOrder
import kotlin.time.Duration.Companion.seconds

/**
 * Backs the bulk novel source filter screen: lists every installed light-novel source grouped by
 * language, and every language the plugin repos and novel app stores offer, with a per-language
 * switch toggling [ReikaiSourcePreferences.disabledNovelLanguages] and per-source checkboxes toggling
 * [ReikaiSourcePreferences.disabledNovelSources] (mirroring Mihon's manga sources filter). A disabled
 * source or language is hidden from the Sources tab and global search, and a disabled language from
 * what Extensions offers, so this screen is where they are re-enabled.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelSourcesFilterViewModel(
    manager: NovelSourceManager,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val toggleNovelSource: ToggleNovelSource,
    registries: LnRepoRegistries,
    extensionManager: ExtensionManager,
) : ViewModel() {

    // What the plugin repos and the novel app stores offer, so a language can be switched off before
    // anything of it is installed. Seeded empty, so the screen never waits on a repo download.
    private val listedLanguages = combine(
        registries.results.onStart { emit(emptyMap()) },
        extensionManager.availableNovelExtensionsFlow,
    ) { results, apps ->
        results.listedPlugins().map { it.lang } +
            apps.flatMap { app -> app.sources.map { it.lang } }
    }

    val state: StateFlow<State> = combine(
        manager.loadedSources(),
        listedLanguages,
        sourcePreferences.disabledNovelSources.changes(),
        sourcePreferences.disabledNovelLanguages.changes(),
    ) { sources, listed, disabled, disabledLanguages ->
        val disabledCodes = disabledLanguages.mapTo(HashSet()) { it.toLangCode() }
        State.Success(
            items = filterLanguages(sources, listed, disabled, disabledCodes),
            disabledSources = disabled,
            disabledLanguages = disabledCodes,
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State.Loading)

    fun toggleSource(sourceId: String) = toggleNovelSource.await(sourceId)

    /** [language] is a code; a switch saved under a plugin's own name for it is turned back on too. */
    fun toggleLanguage(language: String) {
        val pref = sourcePreferences.disabledNovelLanguages
        val current = pref.get()
        val saved = current.filterTo(HashSet()) { it.toLangCode() == language }
        pref.set(if (saved.isNotEmpty()) current - saved else current + language)
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

            /** Two copies of one site differ only by packaging, so each row names its own. */
            val showsFormat: Boolean = NovelExtensionFormat.tellsApart(items.flatMap { it.second }.map { it.format })
        }
    }
}

/**
 * The installed [sources] by language, plus each language a listing declares with nothing installed in
 * it, as Mihon's extension filter lists every language its stores offer. Ordered as the manga half of the
 * screen is, by the sources filter's own order: switched-off languages and sources after the rest.
 */
internal fun filterLanguages(
    sources: List<NovelSource>,
    listed: Collection<String>,
    disabledSources: Set<String>,
    disabledLanguages: Set<String>,
): List<Pair<String, List<NovelSource>>> {
    val sourceOrder = sourcesFilterSourceOrder<NovelSource>({ it.id in disabledSources }, { it.name })
    val installed = sources.groupBy { it.lang }.mapValues { (_, inLanguage) -> inLanguage.sortedWith(sourceOrder) }
    return (installed.keys + listed.map { it.toLangCode() })
        .sortedWith(sourcesFilterLanguageOrder { it in disabledLanguages })
        .map { it to installed[it].orEmpty() }
}
