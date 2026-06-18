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
 * Lists the installed light-novel sources for the Browse → Sources tab (Novels chip). Loads the
 * persisted plugins once via [LnPluginInstaller.ensureLoaded], then follows [NovelSourceManager],
 * grouping by language like Mihon's manga sources list. Tapping a source is stubbed until per-source
 * novel browse lands (S3b).
 */
class NovelSourcesScreenModel(
    manager: NovelSourceManager = Injekt.get(),
    private val installer: LnPluginInstaller = Injekt.get(),
    private val sourcePreferences: ReikaiSourcePreferences = Injekt.get(),
) : StateScreenModel<NovelSourcesScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            installer.ensureLoaded()
            combine(
                manager.sources,
                sourcePreferences.pinnedNovelSources.changes(),
            ) { sources, pinned -> sources.toUiModels(pinned) }
                .collectLatest { items -> mutableState.update { it.copy(isLoading = false, items = items) } }
        }
    }

    fun togglePin(sourceId: String) {
        val pref = sourcePreferences.pinnedNovelSources
        val current = pref.get()
        pref.set(if (sourceId in current) current - sourceId else current + sourceId)
    }

    private fun List<NovelSource>.toUiModels(pinned: Set<String>): List<NovelSourceUiModel> {
        // Pinned sources lead in their own section (mirrors the manga sources list); each remaining
        // source stays in its language group. A pinned source shows only in the Pinned section.
        val pinnedSources = filter { it.id in pinned }.sortedBy { it.name.lowercase() }
        val byLanguage = filterNot { it.id in pinned }
            .groupBy { it.lang }
            .toSortedMap(LocaleHelper.comparator)
        return buildList {
            if (pinnedSources.isNotEmpty()) {
                add(NovelSourceUiModel.Header(PINNED_KEY))
                pinnedSources.forEach { add(NovelSourceUiModel.Item(it, isPinned = true)) }
            }
            byLanguage.forEach { (lang, sources) ->
                add(NovelSourceUiModel.Header(lang))
                sources.sortedBy { it.name.lowercase() }
                    .forEach { add(NovelSourceUiModel.Item(it, isPinned = false)) }
            }
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val items: List<NovelSourceUiModel> = emptyList(),
    ) {
        val isEmpty get() = items.isEmpty()
    }

    companion object {
        // Matches Mihon's SourcesScreenModel.PINNED_KEY so LocaleHelper renders the "Pinned" header.
        const val PINNED_KEY = "pinned"
    }
}

sealed interface NovelSourceUiModel {
    data class Header(val language: String) : NovelSourceUiModel
    data class Item(val source: NovelSource, val isPinned: Boolean = false) : NovelSourceUiModel
}
