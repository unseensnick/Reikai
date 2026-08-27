package reikai.presentation.browse.source

import androidx.lifecycle.ViewModel
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.SourceKey
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager

/**
 * The installed light-novel sources, as the shared Sources list's novel provider. Loads the
 * persisted plugins once via [LnPluginInstaller.ensureLoaded], then follows [NovelSourceManager].
 *
 * Sectioning, the chip and the row dialog belong to [SourcesEngine]: they describe the whole list,
 * which this only ever sees half of.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelSourcesViewModel(
    manager: NovelSourceManager,
    private val installer: LnPluginInstaller,
    private val sourcePreferences: ReikaiSourcePreferences,
) : ViewModel() {

    /** Enabled sources, ungrouped. Null until the plugin host has answered once. */
    val sources: Flow<List<NovelSourceEntry>?> = combine(
        manager.sources,
        sourcePreferences.pinnedNovelSources.changes(),
        sourcePreferences.disabledNovelSources.changes(),
        sourcePreferences.disabledNovelLanguages.changes(),
        sourcePreferences.lastUsedSource.changes(),
    ) { sources, pinned, disabled, disabledLangs, lastUsed ->
        sources.toEntries(pinned, disabled, disabledLangs, (lastUsed as? SourceKey.Novel)?.id)
    }
        .onStart<List<NovelSourceEntry>?> {
            emit(null)
            // The plugin host has to be loaded before the source list means anything, and this runs
            // on every (re)subscription now that the list is not always-on. ensureLoaded is idempotent.
            installer.ensureLoaded()
        }
        .flowOn(Dispatchers.IO)

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

    /**
     * Disabled sources and languages drop out entirely, as on the manga side; they are re-enabled
     * from the Sources filter screen and stay out of global search too.
     *
     * The last-used source is emitted a second time carrying the flag, rather than being lifted out
     * of its language, because that is the form the manga list has and the two now section together.
     */
    private fun List<NovelSource>.toEntries(
        pinned: Set<String>,
        disabled: Set<String>,
        disabledLangs: Set<String>,
        lastUsedId: String?,
    ): List<NovelSourceEntry> {
        val enabled = filterNot { it.id in disabled || it.lang in disabledLangs }
        return enabled.flatMap { source ->
            val entry = NovelSourceEntry(source, isPinned = source.id in pinned, isUsedLast = false)
            if (source.id == lastUsedId) listOf(entry, entry.copy(isUsedLast = true)) else listOf(entry)
        }
    }
}

/** One installed plugin plus the two facts the shared sectioning reads off it. */
data class NovelSourceEntry(
    val source: NovelSource,
    val isPinned: Boolean,
    val isUsedLast: Boolean,
)
