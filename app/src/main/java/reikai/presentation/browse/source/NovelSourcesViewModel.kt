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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import kotlin.time.Duration.Companion.seconds

/**
 * Lists the installed light-novel sources for the Browse → Sources tab (Novels chip). Loads the
 * persisted plugins once via [LnPluginInstaller.ensureLoaded], then follows [NovelSourceManager],
 * grouping by language like Mihon's manga sources list. Tapping a source opens its browse screen
 * (wired in ReikaiSourcesTab).
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelSourcesViewModel(
    manager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val novelPreferences: NovelPreferences,
) : ViewModel() {

    private val dialog = MutableStateFlow<Dialog?>(null)

    private val items = combine(
        manager.sources,
        sourcePreferences.pinnedNovelSources.changes(),
        sourcePreferences.disabledNovelSources.changes(),
        sourcePreferences.disabledNovelLanguages.changes(),
        novelPreferences.lastUsedNovelSource().changes(),
    ) { sources, pinned, disabled, disabledLangs, lastUsed ->
        sources.toUiModels(pinned, disabled, disabledLangs, lastUsed)
    }
        // The plugin host has to be loaded before the source list means anything, and this runs on
        // every (re)subscription now that the feed is not always-on. ensureLoaded is idempotent.
        .onStart { installer.ensureLoaded() }

    val state: StateFlow<State> = combine(items, dialog) { items, dialog ->
        State(isLoading = false, items = items, dialog = dialog)
    }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

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

    fun showSourceDialog(source: NovelSource) = dialog.update {
        Dialog(source = source, isPinned = source.id in sourcePreferences.pinnedNovelSources.get())
    }

    fun closeDialog() = dialog.update { null }

    private fun List<NovelSource>.toUiModels(
        pinned: Set<String>,
        disabled: Set<String>,
        disabledLangs: Set<String>,
        lastUsedId: String,
    ): List<NovelSourceUiModel> {
        // The last-used source leads in its own section, then pinned sources, then language groups
        // (mirrors the manga sources list). Each source shows in exactly one section: the last-used
        // one is pulled out of pinned/language, and a pinned source shows only under Pinned.
        // Disabled sources and languages are filtered out entirely (like manga); they are re-enabled
        // from the Sources filter screen and stay excluded from global search (GetEnabledNovelSources).
        val enabled = filterNot { it.id in disabled || it.lang in disabledLangs }
        val lastUsed = lastUsedId.takeIf { it.isNotBlank() }?.let { id -> enabled.firstOrNull { it.id == id } }
        val remaining = enabled.filter { it.id != lastUsed?.id }
        val pinnedSources = remaining.filter { it.id in pinned }.sortedBy { it.name.lowercase() }
        val byLanguage = remaining.filterNot { it.id in pinned }
            .groupBy { it.lang }
            .toSortedMap(LocaleHelper.comparator)
        return buildList {
            if (lastUsed != null) {
                add(NovelSourceUiModel.Header(LAST_USED_KEY))
                add(NovelSourceUiModel.Item(lastUsed, isPinned = lastUsed.id in pinned))
            }
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
        val dialog: Dialog? = null,
    ) {
        val isEmpty get() = items.isEmpty()
    }

    data class Dialog(val source: NovelSource, val isPinned: Boolean)

    companion object {
        // Match Mihon's SourcesViewModel keys so LocaleHelper renders the "Pinned" / "Last used" headers.
        const val PINNED_KEY = "pinned"
        const val LAST_USED_KEY = "last_used"
    }
}

sealed interface NovelSourceUiModel {
    data class Header(val language: String) : NovelSourceUiModel
    data class Item(
        val source: NovelSource,
        val isPinned: Boolean = false,
    ) : NovelSourceUiModel
}
