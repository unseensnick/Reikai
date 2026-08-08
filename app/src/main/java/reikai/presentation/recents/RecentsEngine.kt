package reikai.presentation.recents

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * One rendered surface's recent activity, assembled over its per-type [RecentsProvider]s. Everything
 * describing the list (the chip, the ordered rows, loading, emptiness, the filter reason, the
 * last-updated line) is owned here and stored once; storing it per content type is what let the two
 * replaced screens disagree with themselves. Anything describing one type stays on a provider.
 * [lanes] is the surface's, not the chip's: every provider's lanes always run, and the chip only
 * selects whose rows assemble. Record: content-layer-recents-surface.md.
 */
class RecentsEngine(
    private val providers: List<RecentsProvider>,
    private val surface: RecentsSurface,
    private val lanes: Set<RecentsLaneKind>,
    private val sourcePreferences: ReikaiSourcePreferences = Injekt.get(),
    private val updatesPreferences: UpdatesPreferences = Injekt.get(),
) : ViewModel() {

    companion object {
        val PROVIDERS_KEY = CreationExtras.Key<List<RecentsProvider>>()
        val SURFACE_KEY = CreationExtras.Key<RecentsSurface>()
        val LANES_KEY = CreationExtras.Key<Set<RecentsLaneKind>>()

        /**
         * Only the first [androidx.lifecycle.viewmodel.compose.viewModel] call for a given store builds
         * the engine; later calls return that instance and ignore this factory. That is what keeps
         * exactly one adapter pair alive, so do not "fix" it into something that runs per composition.
         */
        val Factory = viewModelFactory {
            initializer {
                RecentsEngine(
                    providers = get(PROVIDERS_KEY)!!,
                    surface = get(SURFACE_KEY)!!,
                    lanes = get(LANES_KEY)!!,
                )
            }
        }
    }

    init {
        // Both are combined over, and `combine` of nothing never emits, so an empty one would leave the
        // surface loading forever rather than failing.
        require(providers.isNotEmpty()) { "A recents engine needs at least one provider" }
        require(lanes.isNotEmpty()) { "A recents engine needs at least one lane to render" }
    }

    /**
     * The Manga / Novels chip, one per rendered surface. It decides which providers' rows assemble,
     * which is the engine's call and not one content type's.
     */
    val contentType: StateFlow<ContentType> by lazy {
        chipPreference.changes().stateIn(viewModelScope, SharingStarted.Eagerly, chipPreference.get())
    }

    fun setContentType(type: ContentType) = chipPreference.set(type)

    private val chipPreference: Preference<ContentType>
        get() = when (surface) {
            RecentsSurface.UPDATES -> sourcePreferences.updatesContentType
            RecentsSurface.HISTORY -> sourcePreferences.historyContentType
            RecentsSurface.RECENTS -> sourcePreferences.recentsContentType
        }

    /**
     * The one ordered stream every render policy draws from, tagged with the chip that produced it
     * because the flow lags a chip flip by one emission and a policy must not render the wrong one.
     * Collapsing is not done here: its scope is a policy's decision (see [RecentsAssembly]).
     * `by lazy` like every scope-touching member, so the engine can be constructed in a unit test.
     */
    val assembled: StateFlow<RecentsAssembled?> by lazy {
        combine(
            contentType,
            combine(providers.map(::collectedLanes)) { it.toList() },
            // Every provider's, not just the active ones': the keys are EntryIds and group ids are
            // unique across both content types, so one map serves whatever the chip ends up showing.
            combine(providers.map { it.membership }) { maps -> maps.fold(emptyMap<EntryId, Long>()) { a, b -> a + b } },
        ) { chip, lanesPerProvider, membership ->
            val active = activeIndices(chip).flatMap { lanesPerProvider[it] }
            RecentsAssembled(
                chip = chip,
                items = orderRecents(active.flatMap { it.items }),
                membership = membership,
                // Over the active providers only: an unloaded novel lane used to hold the manga chip's
                // spinner, since one flag was read for a list the other type was not in.
                loading = active.any { !it.loaded },
            )
        }
            // The transform sorts the whole feed; keep it off the main thread.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    }

    /** When the library behind the current chip last updated, the newer of the two under All. */
    val lastUpdated: StateFlow<Long> by lazy {
        combine(
            contentType,
            combine(providers.map { it.lastUpdated }) { it.toList() },
        ) { chip, perProvider ->
            activeIndices(chip).maxOfOrNull { perProvider[it] } ?: 0L
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)
    }

    /** Whether a filter is narrowing this surface, so an empty feed can say why. */
    val filterActive: StateFlow<Boolean> by lazy {
        combine(
            sourcePreferences.recentsCategoryFilterFlow(surface).map { it.active },
            chapterStateFilterActive(),
        ) { byCategory, byChapterState ->
            recentsFilterActive(byCategory, byChapterState, lanes)
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    }

    private fun chapterStateFilterActive(): Flow<Boolean> = combine(
        updatesPreferences.filterUnread.changes(),
        updatesPreferences.filterDownloaded.changes(),
        updatesPreferences.filterStarted.changes(),
        updatesPreferences.filterBookmarked.changes(),
    ) { filters -> filters.any { it != TriState.DISABLED } }

    /** Every lane this surface renders, from one provider. Always collected, whatever the chip is. */
    private fun collectedLanes(provider: RecentsProvider): Flow<List<RecentsLaneRows>> =
        combine(lanes.map(provider::lane)) { it.toList() }

    private fun activeIndices(chip: ContentType): List<Int> =
        providers.indices.filter { chip == ContentType.ALL || providers[it].contentType == chip }
}

/**
 * The chapter-state filters count only where the updated lane renders. The read and added lanes are
 * not filtered by them, so History would otherwise report itself filtered because of a filter set on
 * Updates, and send a user looking for rows nothing is hiding.
 */
internal fun recentsFilterActive(
    byCategory: Boolean,
    byChapterState: Boolean,
    lanes: Set<RecentsLaneKind>,
): Boolean = byCategory || (byChapterState && RecentsLaneKind.UPDATED in lanes)

/**
 * One assembly pass: the ordered rows and what the surface can say about them. [chip] is what the rows
 * were selected by, which the renderer compares against the live chip before drawing them.
 * [membership] rides along rather than being read separately, so a policy collapsing merged series can
 * never pair one emission's rows with another's groups.
 */
@Immutable
data class RecentsAssembled(
    val chip: ContentType,
    val items: List<RecentsItem>,
    val loading: Boolean,
    val membership: Map<EntryId, Long> = emptyMap(),
) {
    /** Empty means empty, never "not here yet"; the two want different things on screen. */
    val isEmpty: Boolean get() = !loading && items.isEmpty()
}
