package eu.kanade.presentation.more.settings.screen.novel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import reikai.domain.novel.NovelPreferences
import reikai.novel.download.NovelDownloadPacing
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO
import kotlin.time.Duration.Companion.seconds

/** Each installed novel source with the delay the downloader keeps between its chapters. */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelSourceDelaysViewModel(
    sourceManager: NovelSourceManager,
    private val novelPreferences: NovelPreferences,
) : ViewModel() {

    private val loaded = MutableStateFlow(false)

    init {
        // The registry fills only when asked, and nothing else may have asked yet.
        viewModelScope.launchIO {
            sourceManager.ensureLoaded()
            loaded.value = true
        }
    }

    val state: StateFlow<State> = combine(
        loaded,
        sourceManager.sources,
        novelPreferences.downloadSourceDelays().changes(),
        novelPreferences.downloadChapterDelayMs().changes(),
    ) { isLoaded, sources, entries, globalMs ->
        val delays = NovelDownloadPacing.parse(entries)
        State(
            sources = sources
                .sortedBy { it.name.lowercase() }
                .map { SourceDelay(it.id, it.name, it.lang, delays[it.id], it.minimumRequestDelayMs) }
                .takeIf { isLoaded },
            globalMs = globalMs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    /** Gives [sourceId] its own delay, or hands it back to the global one when [delayMs] is null. */
    fun setDelay(sourceId: String, delayMs: Long?) {
        val preference = novelPreferences.downloadSourceDelays()
        val delays = NovelDownloadPacing.parse(preference.get())
        val updated = if (delayMs == null) delays - sourceId else delays + (sourceId to delayMs)
        preference.set(NovelDownloadPacing.format(updated))
    }

    /** [delayMs] is the source's own delay as stored; the downloader lifts it to [minimumMs]. */
    @Immutable
    data class SourceDelay(
        val id: String,
        val name: String,
        val lang: String,
        val delayMs: Long?,
        val minimumMs: Long,
    )

    /** [sources] is null until the plugins have loaded. */
    @Immutable
    data class State(val sources: List<SourceDelay>? = null, val globalMs: Long = 0L)
}
