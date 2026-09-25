package reikai.presentation.browse.extension

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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import reikai.domain.library.ContentType
import reikai.domain.library.includes
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.source.NovelExtensionFormat
import reikai.presentation.browse.debouncedBrowseQuery
import kotlin.time.Duration.Companion.seconds

/**
 * Assembles the Browse Extensions list from both content types.
 *
 * All is the real list and the chip is a predicate over it, so everything describing the list is one
 * value here rather than one per type: the sections, the search, whether it is loading, refreshing
 * or empty, and whether the install-permission banner applies. A provider only answers about its own
 * extensions.
 */
@AssistedInject
class ExtensionsEngine(
    // Assisted: each provider wraps a ViewModel the tab has already resolved, and the query is the
    // Browse search bar's, which is hoisted above the tabs.
    @Assisted private val providers: List<ExtensionsProvider>,
    @Assisted private val query: StateFlow<String?>,
    private val sourcePreferences: ReikaiSourcePreferences,
) : ViewModel() {

    val state: StateFlow<State> = combine(
        combine(providers.map { it.snapshot() }) { it.toList() },
        sourcePreferences.browseContentType.changes(),
        // Debounced because the available list runs to thousands of rows and every keystroke
        // re-filters and re-sorts all of them; the search field itself stays live either way.
        query.debouncedBrowseQuery(),
    ) { snapshots, contentType, query ->
        val active = providers.indices.filter { providers[it].shows(contentType) }
        // A provider serving both types is active under either chip, so its rows are filtered too.
        val rows = active.flatMap { snapshots[it].rows.orEmpty() }.filter { contentType.includes(it.key.contentType) }
        val shown = rows.filter { matchesExtensionQuery(it, query) }
        State(
            contentType = contentType,
            query = query,
            // One value each over the active providers: a chip must never be gated on a list it is
            // not showing, which is what a flag per content type lets happen.
            // Only while nothing has answered: the light-novel half waits on network, and holding
            // the whole list back for it hid manga extensions that were ready immediately.
            isLoading = active.all { snapshots[it].rows == null },
            hasPending = active.any { snapshots[it].rows == null },
            isRefreshing = active.any { snapshots[it].isRefreshing },
            hasRepos = active.any { i -> snapshots[i].reposFor.any { contentType.includes(it) } },
            // Only where a row on screen installs through the system, so a plugin-only Novels list
            // does not ask for a permission nothing it shows would use.
            needsInstallPermission = active.any { i ->
                snapshots[i].needsInstallPermission &&
                    snapshots[i].rows.orEmpty().any { contentType.includes(it.key.contentType) }
            },
            showsFormat = NovelExtensionFormat.tellsApart(shown.map { it.key.format }),
            items = sectionExtensions(shown),
        )
    }
        // Off the main thread: building, filtering and sorting the list is proportional to every
        // extension the repos offer, which is thousands once a few languages are enabled.
        .flowOn(Dispatchers.IO)
        // The typed query itself is not debounced, so the back gesture and the empty-state message
        // answer to what is in the field rather than to what the list last filtered on.
        .combine(query) { state, typed -> state.copy(query = typed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    fun setContentType(contentType: ContentType) {
        sourcePreferences.browseContentType.set(contentType)
    }

    fun refresh() = activeProviders().forEach { it.refresh() }

    /**
     * Update everything under the Updates header, across both content types. Scoped to the rows on
     * screen, so a search narrows what Update all touches, as the manga list already did.
     */
    fun updateAll() {
        val pending = state.value.items
            .filterIsInstance<ExtensionsListItem.Row>()
            .map { it.row }
            .filter { it.section == ExtensionSection.Updates }
        activeProviders().forEach { provider ->
            provider.updateAll(pending.filter { it.key.contentType in provider.contentTypes })
        }
    }

    private fun activeProviders() = providers.filter { it.shows(state.value.contentType) }

    private fun ExtensionsProvider.shows(contentType: ContentType) = contentTypes.any { contentType.includes(it) }

    private fun ExtensionsProvider.snapshot(): Flow<Snapshot> =
        combine(rows, reposFor, isRefreshing, needsInstallPermission, ::Snapshot)

    private data class Snapshot(
        val rows: List<BrowseExtensionRow>?,
        val reposFor: Set<ContentType>,
        val isRefreshing: Boolean,
        val needsInstallPermission: Boolean,
    )

    @Immutable
    data class State(
        val contentType: ContentType = ContentType.ALL,
        val query: String? = null,
        val isLoading: Boolean = true,
        /** A content type that has not answered yet, so the list is showing part of itself. */
        val hasPending: Boolean = true,
        val isRefreshing: Boolean = false,
        val hasRepos: Boolean = true,
        val needsInstallPermission: Boolean = false,
        /** Novel rows of more than one packaging are on screen, so each row names its own. */
        val showsFormat: Boolean = false,
        val items: List<ExtensionsListItem> = emptyList(),
    ) {
        // A half still on its way must not read as "nothing found".
        val isEmpty get() = items.isEmpty() && !hasPending
        val isSearching get() = !query.isNullOrBlank()
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(providers: List<ExtensionsProvider>, query: StateFlow<String?>): ExtensionsEngine
    }
}
