package reikai.presentation.browse.source

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import reikai.domain.library.ContentType
import reikai.domain.source.ReikaiSourcePreferences
import kotlin.time.Duration.Companion.seconds

/**
 * Assembles the Browse Sources list from both content types.
 *
 * All is the real list and the chip is a predicate over it, so everything describing the list is one
 * value here rather than one per type: the sections, whether it is still loading, whether it is
 * empty, and the row dialog. A provider only answers about its own sources.
 */
@AssistedInject
class SourcesEngine(
    // Assisted: each provider wraps a ViewModel the tab has already resolved.
    @Assisted private val providers: List<SourcesProvider>,
    private val sourcePreferences: ReikaiSourcePreferences,
) : ViewModel() {

    private val dialog = MutableStateFlow<SourceOptionsDialog?>(null)

    val state: StateFlow<State> = combine(
        combine(providers.map { it.rows }) { it.toList() },
        sourcePreferences.browseContentType.changes(),
    ) { rowsPerProvider, contentType ->
        val active = providers.indices.filter { providers[it].shows(contentType) }
        State(
            contentType = contentType,
            // One loading state over the active providers: a chip must never be gated on a list it
            // is not showing, which is what two per-type flags let happen.
            isLoading = active.any { rowsPerProvider[it] == null },
            items = sectionSources(active.flatMap { rowsPerProvider[it].orEmpty() }),
        )
    }
        // Off the main thread: sectioning sorts and groups every enabled source of both types.
        .flowOn(Dispatchers.IO)
        // The sheet is combined in afterwards rather than being an input above, so opening or
        // closing it does not re-section the list it is opened over.
        .combine(dialog) { state, dialog -> state.copy(dialog = dialog) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    fun setContentType(contentType: ContentType) {
        sourcePreferences.browseContentType.set(contentType)
    }

    fun togglePin(row: BrowseSourceRow) = providerFor(row).togglePin(row)

    fun toggleDisable(row: BrowseSourceRow) = providerFor(row).toggleDisable(row)

    fun showDialog(row: BrowseSourceRow) = dialog.update {
        SourceOptionsDialog(row = row, canDisable = providerFor(row).canDisable(row))
    }

    fun closeDialog() = dialog.update { null }

    private fun providerFor(row: BrowseSourceRow) =
        providers.first { it.contentType == row.key.contentType }

    private fun SourcesProvider.shows(contentType: ContentType) =
        contentType == ContentType.ALL || contentType == this.contentType

    @Immutable
    data class State(
        val contentType: ContentType = ContentType.ALL,
        val isLoading: Boolean = true,
        val items: List<SourcesListItem> = emptyList(),
        val dialog: SourceOptionsDialog? = null,
    ) {
        val isEmpty get() = items.isEmpty()
    }

    /** The long-press sheet on a row, built here because only the engine knows which type it came from. */
    data class SourceOptionsDialog(val row: BrowseSourceRow, val canDisable: Boolean)

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(providers: List<SourcesProvider>): SourcesEngine
    }
}
