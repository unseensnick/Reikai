package reikai.presentation.browse.source

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.util.system.LocaleHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import reikai.domain.novel.NovelPreferences
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
    private val novelPreferences: NovelPreferences = Injekt.get(),
) : StateScreenModel<NovelSourcesScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            installer.ensureLoaded()
            combine(
                manager.sources,
                sourcePreferences.pinnedNovelSources.changes(),
                sourcePreferences.disabledNovelSources.changes(),
                novelPreferences.lastUsedNovelSource().changes(),
            ) { sources, pinned, disabled, lastUsed -> sources.toUiModels(pinned, disabled, lastUsed) }
                .collectLatest { items -> mutableState.update { it.copy(isLoading = false, items = items) } }
        }
    }

    fun togglePin(sourceId: String) {
        val pref = sourcePreferences.pinnedNovelSources
        val current = pref.get()
        pref.set(if (sourceId in current) current - sourceId else current + sourceId)
    }

    fun toggleDisable(sourceId: String) {
        val pref = sourcePreferences.disabledNovelSources
        val current = pref.get()
        pref.set(if (sourceId in current) current - sourceId else current + sourceId)
    }

    fun showSourceDialog(source: NovelSource) = mutableState.update {
        it.copy(
            dialog = Dialog(
                source = source,
                isPinned = source.id in sourcePreferences.pinnedNovelSources.get(),
                isDisabled = source.id in sourcePreferences.disabledNovelSources.get(),
            ),
        )
    }

    fun closeDialog() = mutableState.update { it.copy(dialog = null) }

    private fun List<NovelSource>.toUiModels(
        pinned: Set<String>,
        disabled: Set<String>,
        lastUsedId: String,
    ): List<NovelSourceUiModel> {
        // The last-used source leads in its own section, then pinned sources, then language groups
        // (mirrors the manga sources list). Each source shows in exactly one section: the last-used
        // one is pulled out of pinned/language, and a pinned source shows only under Pinned.
        // Disabled sources stay in the list (rendered dimmed) so they can be re-enabled; they are
        // excluded from global search instead (see GetEnabledNovelSources).
        val lastUsed = lastUsedId.takeIf { it.isNotBlank() }?.let { id -> firstOrNull { it.id == id } }
        val remaining = filter { it.id != lastUsed?.id }
        val pinnedSources = remaining.filter { it.id in pinned }.sortedBy { it.name.lowercase() }
        val byLanguage = remaining.filterNot { it.id in pinned }
            .groupBy { it.lang }
            .toSortedMap(LocaleHelper.comparator)
        return buildList {
            if (lastUsed != null) {
                add(NovelSourceUiModel.Header(LAST_USED_KEY))
                add(
                    NovelSourceUiModel.Item(
                        lastUsed,
                        isPinned = lastUsed.id in pinned,
                        isDisabled = lastUsed.id in disabled,
                    ),
                )
            }
            if (pinnedSources.isNotEmpty()) {
                add(NovelSourceUiModel.Header(PINNED_KEY))
                pinnedSources.forEach {
                    add(NovelSourceUiModel.Item(it, isPinned = true, isDisabled = it.id in disabled))
                }
            }
            byLanguage.forEach { (lang, sources) ->
                add(NovelSourceUiModel.Header(lang))
                sources.sortedBy { it.name.lowercase() }
                    .forEach { add(NovelSourceUiModel.Item(it, isPinned = false, isDisabled = it.id in disabled)) }
            }
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val items: List<NovelSourceUiModel> = emptyList(),
        val dialog: Dialog? = null,
    ) {
        val isEmpty get() = items.isEmpty()
    }

    data class Dialog(val source: NovelSource, val isPinned: Boolean, val isDisabled: Boolean)

    companion object {
        // Match Mihon's SourcesScreenModel keys so LocaleHelper renders the "Pinned" / "Last used" headers.
        const val PINNED_KEY = "pinned"
        const val LAST_USED_KEY = "last_used"
    }
}

sealed interface NovelSourceUiModel {
    data class Header(val language: String) : NovelSourceUiModel
    data class Item(
        val source: NovelSource,
        val isPinned: Boolean = false,
        val isDisabled: Boolean = false,
    ) : NovelSourceUiModel
}
