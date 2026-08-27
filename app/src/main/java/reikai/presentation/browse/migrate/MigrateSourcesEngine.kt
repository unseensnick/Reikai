package reikai.presentation.browse.migrate

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
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import reikai.domain.library.ContentType
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.preference.getAndSet
import kotlin.time.Duration.Companion.seconds

/**
 * Assembles the Browse migrate-from list from both content types.
 *
 * All is the real list and the chip is a predicate over it, so everything describing the list is one
 * value here rather than one per type: the order, whether it is still loading, and whether it is
 * empty. One sort header therefore covers both types, which is what retires the old carve-out that
 * left the All view unsorted.
 */
@AssistedInject
class MigrateSourcesEngine(
    // Assisted: each provider wraps a ViewModel the tab has already resolved.
    @Assisted private val providers: List<MigrateSourcesProvider>,
    private val sourcePreferences: ReikaiSourcePreferences,
    private val mihonSourcePreferences: SourcePreferences,
) : ViewModel() {

    val state: StateFlow<State> = combine(
        combine(providers.map { it.rows }) { it.toList() },
        sourcePreferences.browseContentType.changes(),
        mihonSourcePreferences.migrationSortingMode.changes(),
        mihonSourcePreferences.migrationSortingDirection.changes(),
    ) { rowsPerProvider, contentType, mode, direction ->
        val active = providers.indices.filter { providers[it].shows(contentType) }
        State(
            contentType = contentType,
            // One loading state over the active providers: a chip must never be gated on a list it
            // is not showing. Only while nothing has answered, so a half that is still loading no
            // longer holds back the half that is ready.
            isLoading = active.all { rowsPerProvider[it] == null },
            hasPending = active.any { rowsPerProvider[it] == null },
            items = active.flatMap { rowsPerProvider[it].orEmpty() }
                .sortedWith(compareMigrateRows(mode, direction)),
            sortingMode = mode,
            sortingDirection = direction,
        )
    }
        // Off the main thread: the sort runs over every source of both types, and re-runs whenever
        // the chip or either sort preference changes.
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    fun setContentType(contentType: ContentType) {
        sourcePreferences.browseContentType.set(contentType)
    }

    // Both toggles read the preference, never state.value: the shared state answers its seed while
    // nothing is subscribed, so reading it here would flip the sort back to the default.
    fun toggleSortingMode() {
        mihonSourcePreferences.migrationSortingMode.getAndSet { mode ->
            when (mode) {
                SetMigrateSorting.Mode.ALPHABETICAL -> SetMigrateSorting.Mode.TOTAL
                SetMigrateSorting.Mode.TOTAL -> SetMigrateSorting.Mode.ALPHABETICAL
            }
        }
    }

    fun toggleSortingDirection() {
        mihonSourcePreferences.migrationSortingDirection.getAndSet { direction ->
            when (direction) {
                SetMigrateSorting.Direction.ASCENDING -> SetMigrateSorting.Direction.DESCENDING
                SetMigrateSorting.Direction.DESCENDING -> SetMigrateSorting.Direction.ASCENDING
            }
        }
    }

    private fun MigrateSourcesProvider.shows(contentType: ContentType) =
        contentType == ContentType.ALL || contentType == this.contentType

    @Immutable
    data class State(
        val contentType: ContentType = ContentType.ALL,
        val isLoading: Boolean = true,
        /** A content type that has not answered yet, so the list is showing part of itself. */
        val hasPending: Boolean = true,
        val items: List<BrowseMigrateRow> = emptyList(),
        val sortingMode: SetMigrateSorting.Mode = SetMigrateSorting.Mode.ALPHABETICAL,
        val sortingDirection: SetMigrateSorting.Direction = SetMigrateSorting.Direction.ASCENDING,
    ) {
        // A half still on its way must not read as "nothing found".
        val isEmpty get() = items.isEmpty() && !hasPending
    }

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(providers: List<MigrateSourcesProvider>): MigrateSourcesEngine
    }
}
