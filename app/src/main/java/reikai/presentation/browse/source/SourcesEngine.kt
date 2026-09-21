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
import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.domain.source.interactor.ToggleIncognito
import eu.kanade.domain.source.service.SourcePreferences
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
import reikai.novel.source.NovelExtensionFormat
import reikai.presentation.browse.debouncedBrowseQuery
import tachiyomi.core.common.util.lang.launchIO
import kotlin.time.Duration.Companion.seconds

/**
 * Assembles the Browse Sources list from both content types.
 *
 * All is the real list and the chip is a predicate over it, so everything describing the list is one
 * value here rather than one per type: the sections, the search, whether it is still loading, whether
 * it is empty, and the row dialog. A provider only answers about its own sources.
 */
@AssistedInject
class SourcesEngine(
    // Assisted: each provider wraps a ViewModel the tab has already resolved, and the query is the
    // Browse search bar's, which sits above the tabs.
    @Assisted private val providers: List<SourcesProvider>,
    @Assisted private val query: StateFlow<String?>,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val incognitoPreferences: SourcePreferences,
    private val getIncognitoState: GetIncognitoState,
    private val toggleIncognito: ToggleIncognito,
) : ViewModel() {

    private val dialog = MutableStateFlow<SourceOptionsDialog?>(null)

    val state: StateFlow<State> = combine(
        combine(providers.map { it.rows }) { it.toList() },
        sourcePreferences.browseContentType.changes(),
        // Debounced so a burst of typing re-sections every source once rather than per keystroke.
        query.debouncedBrowseQuery(),
    ) { rowsPerProvider, contentType, query ->
        val active = providers.indices.filter { providers[it].shows(contentType) }
        val shown = active.flatMap { rowsPerProvider[it].orEmpty() }.filter { matchesSourceQuery(it, query) }
        State(
            contentType = contentType,
            // One loading state over the active providers: a chip must never be gated on a list it
            // is not showing. Only while nothing has answered, so a half that is still loading no
            // longer holds back the half that is ready.
            isLoading = active.all { rowsPerProvider[it] == null },
            hasPending = active.any { rowsPerProvider[it] == null },
            showsFormat = NovelExtensionFormat.tellsApart(shown.map { it.format }),
            items = sectionSources(shown),
        )
    }
        // Off the main thread: sectioning sorts and groups every enabled source of both types.
        .flowOn(Dispatchers.IO)
        // Combined in afterwards rather than being inputs above, so none of them re-sections the list.
        // The typed query is not debounced, so the empty-state message answers to what is in the field.
        .combine(dialog) { state, dialog -> state.copy(dialog = dialog) }
        .combine(sourcePreferences.hideSourceLatestButton.changes()) { state, hide -> state.copy(showLatest = !hide) }
        .combine(query) { state, typed -> state.copy(query = typed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    fun setContentType(contentType: ContentType) {
        sourcePreferences.browseContentType.set(contentType)
    }

    fun togglePin(row: BrowseSourceRow) = providerFor(row).togglePin(row)

    fun toggleDisable(row: BrowseSourceRow) = providerFor(row).toggleDisable(row)

    fun showDialog(row: BrowseSourceRow) {
        viewModelScope.launchIO {
            val incognitoKey = getIncognitoState.incognitoKey(row.key)
            dialog.update {
                SourceOptionsDialog(
                    row = row,
                    canDisable = providerFor(row).canDisable(row),
                    canToggleIncognito = incognitoKey != null,
                    isIncognito = incognitoKey in incognitoPreferences.incognitoExtensions.get(),
                )
            }
        }
    }

    fun toggleIncognito(dialog: SourceOptionsDialog) {
        viewModelScope.launchIO {
            val incognitoKey = getIncognitoState.incognitoKey(dialog.row.key) ?: return@launchIO
            toggleIncognito.await(incognitoKey, enable = !dialog.isIncognito)
            this@SourcesEngine.dialog.update { open ->
                open?.takeIf { it.row == dialog.row }?.copy(isIncognito = !dialog.isIncognito) ?: open
            }
        }
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
        /** A content type that has not answered yet, so the list is showing part of itself. */
        val hasPending: Boolean = true,
        /** Novel sources of more than one packaging are on screen, so each row names its own. */
        val showsFormat: Boolean = false,
        val items: List<SourcesListItem> = emptyList(),
        val dialog: SourceOptionsDialog? = null,
        /** Whether a row that supports Latest shows its button. */
        val showLatest: Boolean = true,
        val query: String? = null,
    ) {
        // A half still on its way must not read as "nothing found".
        val isEmpty get() = items.isEmpty() && !hasPending

        val isSearching get() = !query.isNullOrBlank()
    }

    /** The long-press sheet on a row, built here because only the engine knows which type it came from. */
    data class SourceOptionsDialog(
        val row: BrowseSourceRow,
        val canDisable: Boolean,
        /** False when there is nothing to store incognito under: a manga source with no extension. */
        val canToggleIncognito: Boolean,
        val isIncognito: Boolean,
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(providers: List<SourcesProvider>, query: StateFlow<String?>): SourcesEngine
    }
}
