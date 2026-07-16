package reikai.presentation.browse.source

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Backs the bulk novel source filter screen: lists every installed light-novel source grouped by
 * language, each toggling [ReikaiSourcePreferences.disabledNovelSources]. A disabled novel source is
 * hidden from the Sources tab (like manga), so this screen is where it is re-enabled.
 */
class NovelSourcesFilterScreenModel(
    manager: NovelSourceManager = Injekt.get(),
    private val installer: LnPluginInstaller = Injekt.get(),
    private val sourcePreferences: ReikaiSourcePreferences = Injekt.get(),
) : StateScreenModel<NovelSourcesFilterScreenModel.State>(State.Loading) {

    init {
        screenModelScope.launchIO {
            installer.ensureLoaded()
            combine(
                manager.sources,
                sourcePreferences.disabledNovelSources.changes(),
            ) { sources, disabled ->
                State.Success(
                    items = sources.groupBy { it.lang }
                        .toSortedMap(LocaleHelper.comparator)
                        .map { (lang, langSources) -> lang to langSources.sortedBy { it.name.lowercase() } },
                    disabledSources = disabled,
                )
            }.collectLatest { success -> mutableState.update { success } }
        }
    }

    fun toggleSource(sourceId: String) {
        val pref = sourcePreferences.disabledNovelSources
        val current = pref.get()
        pref.set(if (sourceId in current) current - sourceId else current + sourceId)
    }

    sealed interface State {
        data object Loading : State

        @Immutable
        data class Success(
            val items: List<Pair<String, List<NovelSource>>>,
            val disabledSources: Set<String>,
        ) : State {
            val isEmpty get() = items.isEmpty()
        }
    }
}
