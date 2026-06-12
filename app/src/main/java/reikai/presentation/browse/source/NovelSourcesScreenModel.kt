package reikai.presentation.browse.source

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
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
) : StateScreenModel<NovelSourcesScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            installer.ensureLoaded()
            manager.sources.collectLatest { sources ->
                mutableState.update { it.copy(isLoading = false, items = sources.toUiModels()) }
            }
        }
    }

    private fun List<NovelSource>.toUiModels(): List<NovelSourceUiModel> =
        groupBy { it.lang }
            .toSortedMap(LocaleHelper.comparator)
            .flatMap { (lang, sources) ->
                buildList {
                    add(NovelSourceUiModel.Header(lang))
                    sources.sortedBy { it.name.lowercase() }
                        .forEach { add(NovelSourceUiModel.Item(it)) }
                }
            }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val items: List<NovelSourceUiModel> = emptyList(),
    ) {
        val isEmpty get() = items.isEmpty()
    }
}

sealed interface NovelSourceUiModel {
    data class Header(val language: String) : NovelSourceUiModel
    data class Item(val source: NovelSource) : NovelSourceUiModel
}
