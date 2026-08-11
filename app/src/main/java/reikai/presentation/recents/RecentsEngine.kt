package reikai.presentation.recents

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cafe.adriel.voyager.core.screen.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import reikai.domain.category.RecentsSurface
import reikai.domain.category.recentsCategoryFilterFlow
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.browse.AddDecision
import reikai.presentation.browse.AddFavoriteResult
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.updates.service.UpdatesPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds

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
         * Anything derived over a provider holds that provider's feed subscription open, so it stops
         * once nothing renders it. The window matches the history models the read lane runs through.
         * A value a verb reads synchronously stays eager instead: unsubscribed, `value` is the seed.
         */
        private val OVER_PROVIDERS = SharingStarted.WhileSubscribed(5.seconds)

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
     * which is the engine's call and not one content type's. Eager, unlike the flows derived over it,
     * because every verb reads this synchronously to pick the providers it dispatches to.
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
            query,
        ) { chip, lanesPerProvider, membership, query ->
            val active = activeIndices(chip).flatMap { lanesPerProvider[it] }
            val rows = orderRecents(active.flatMap { it.items }).filter { matchesQuery(it, query) }
            RecentsAssembled(
                chip = chip,
                items = rows,
                membership = membership,
                // Over the active providers only: an unloaded novel lane used to hold the manga chip's
                // spinner, since one flag was read for a list the other type was not in.
                loading = active.any { !it.loaded },
            )
        }
            // Pruning is an effect on the selection, so it sits beside the transform rather than
            // inside it. Nothing to prune against while nothing collects: the feed is not moving.
            .onEach { pruneSelection(it.items) }
            // The transform sorts the whole feed; keep it off the main thread.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, OVER_PROVIDERS, null)
    }

    /** When the library behind the current chip last updated, the newer of the two under All. */
    val lastUpdated: StateFlow<Long> by lazy {
        combine(
            contentType,
            combine(providers.map { it.lastUpdated }) { it.toList() },
        ) { chip, perProvider ->
            activeIndices(chip).maxOfOrNull { perProvider[it] } ?: 0L
        }.stateIn(viewModelScope, OVER_PROVIDERS, 0L)
    }

    /**
     * Whether a library behind the current chip is updating, so a refreshing indicator ends when the
     * job does. The two replaced screens faked this with a fixed one-second delay, which said nothing
     * about whether anything was actually running.
     */
    val refreshing: StateFlow<Boolean> by lazy {
        combine(
            contentType,
            combine(providers.map { it.updating }) { it.toList() },
        ) { chip, perProvider ->
            activeIndices(chip).any { perProvider[it] }
        }.stateIn(viewModelScope, OVER_PROVIDERS, false)
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

    // One query for the surface, matched here rather than in SQL. Every model already writes the user's
    // custom title into the row it emits, so matching the displayed title is what makes a renamed entry
    // findable by the name on screen; the SQL search could only ever see the source title. It also gives
    // the updated and added lanes a search they have never had, at the cost of one pass over a feed that
    // is bounded per lane.
    private val mutableQuery = MutableStateFlow<String?>(null)
    val query: StateFlow<String?> = mutableQuery.asStateFlow()

    fun search(query: String?) {
        mutableQuery.value = query
    }

    private val providersByType = providers.associateBy { it.contentType }

    private fun matchesQuery(item: RecentsItem, query: String?): Boolean {
        if (query.isNullOrBlank()) return true
        val title = providersByType[item.entryId.contentType]?.title(item) ?: return false
        return title.contains(query, ignoreCase = true)
    }

    // Selection. A chapter, not an entry: two chapters of one series are independently selectable, and
    // the raw chapter ids of the two content types overlap.

    private val mutableSelection = MutableStateFlow<Set<ChapterRef>>(emptySet())
    val selection: StateFlow<Set<ChapterRef>> = mutableSelection.asStateFlow()

    /** Anchor for range-select; not reactive, it only decides how the next long-press behaves. */
    private var lastSelected: ChapterRef? = null

    fun clearSelection() {
        lastSelected = null
        mutableSelection.value = emptySet()
    }

    fun toggleSelection(chapter: ChapterRef) {
        mutableSelection.update { if (chapter in it) it - chapter else it + chapter }
        lastSelected = chapter.takeIf { mutableSelection.value.isNotEmpty() }
    }

    /**
     * Select everything between [chapter] and the last selected row, in the order given. [ordered] is
     * the rendered order, which only the caller knows: it interleaves both content types, and grouping
     * and collapsing change it again. The replaced screen ranged over one model's own list instead, so
     * a sweep under the All chip skipped every row of the other type.
     */
    fun toggleRangeSelection(chapter: ChapterRef, ordered: List<ChapterRef>) {
        val anchor = lastSelected
        val from = ordered.indexOf(anchor)
        val to = ordered.indexOf(chapter)
        mutableSelection.update { current ->
            if (anchor == null || from < 0 || to < 0) {
                current + chapter
            } else {
                current + ordered.subList(minOf(from, to), maxOf(from, to) + 1)
            }
        }
        lastSelected = chapter
    }

    fun selectAll(ordered: List<ChapterRef>) {
        lastSelected = null
        mutableSelection.update { it + ordered }
    }

    fun invertSelection(ordered: List<ChapterRef>) {
        lastSelected = null
        mutableSelection.update { current ->
            val (toRemove, toAdd) = ordered.partition { it in current }
            current - toRemove.toSet() + toAdd
        }
    }

    /** Drop selected chapters that the rendered feed no longer holds, so the toolbar count cannot
     *  promise more than the verbs will touch. */
    private fun pruneSelection(rows: List<RecentsItem>) {
        mutableSelection.update { selection ->
            if (selection.isEmpty()) return@update selection
            val present = rows.mapNotNullTo(HashSet()) { it.lane.chapterRef }
            val pruned = selection.filterTo(HashSet()) { it in present }
            if (pruned.size == selection.size) selection else pruned
        }
    }

    // Dialogs: one slot, so a prompt about a mixed selection is asked once.

    private val mutableDialog = MutableStateFlow<RecentsDialog?>(null)
    val dialog: StateFlow<RecentsDialog?> = mutableDialog.asStateFlow()

    fun openDialog(dialog: RecentsDialog) {
        mutableDialog.value = dialog
    }

    fun dismissDialog() {
        mutableDialog.value = null
    }

    // The verbs. Each is handed to every provider in view, which narrows it to its own rows, so one
    // call covers a selection spanning both content types.

    fun markReadSelection(read: Boolean) = dispatchAndClear { it.markRead(selection.value, read) }

    fun setBookmarkSelection(bookmarked: Boolean) =
        dispatchAndClear { it.setBookmark(selection.value, bookmarked) }

    fun downloadSelection() = dispatchAndClear { it.download(selection.value) }

    fun deleteDownloads(chapters: Set<ChapterRef>) {
        activeProviders().forEach { it.deleteDownloads(chapters) }
        clearSelection()
    }

    fun removeFromHistory(entries: Set<EntryId>) {
        providers.forEach { it.removeFromHistory(entries) }
    }

    // The add flow, owned here rather than by either content type's model, so one shell renders one
    // dialog channel. Each verb below is the UI entry point; the suspend half beside it is the
    // operation, which is also what the tests drive, since nothing can await a launched coroutine.

    /** Adds [entry], asking about a possible duplicate or a category first when the decision needs it. */
    fun addToLibrary(entry: EntryId) {
        viewModelScope.launchIO { startAdd(entry) }
    }

    /** Adds anyway, from the duplicate prompt's confirm. */
    fun addAnyway(entry: EntryId) {
        dismissDialog()
        viewModelScope.launchIO { runAdd(entry) }
    }

    /** Adds [entry] and merges it into the group of the duplicates the user picked in the prompt. */
    fun addToGroup(entry: EntryId, duplicates: List<EntryId>) {
        dismissDialog()
        viewModelScope.launchIO { groupAdd(entry, duplicates) }
    }

    /** The category picker's confirm, which owes both writes the add deferred. */
    fun applyAddCategories(entry: EntryId, categoryIds: List<Long>) {
        dismissDialog()
        viewModelScope.launchIO { fileAddCategories(entry, categoryIds) }
    }

    /** Migrates a duplicate already in the library onto the entry being added, from the prompt. */
    fun migrateOntoEntry(entry: EntryId, duplicate: EntryId) {
        openDialog(RecentsDialog.Migrate(current = duplicate, target = entry))
    }

    /**
     * An entry already in the library is left alone rather than added again: the provider would
     * otherwise refile it, and on the manga side toggle the favorite back off.
     */
    internal suspend fun startAdd(entry: EntryId) {
        val provider = providersByType[entry.contentType] ?: return
        when (val decision = provider.addDecision(entry)) {
            null, AddDecision.Remove -> Unit
            is AddDecision.ConfirmDuplicate -> openDialog(RecentsDialog.Duplicate(entry, decision.duplicates))
            AddDecision.Add -> runAdd(entry)
        }
    }

    internal suspend fun runAdd(entry: EntryId) {
        val provider = providersByType[entry.contentType] ?: return
        promptForCategories(entry, provider.addToLibrary(entry))
    }

    internal suspend fun groupAdd(entry: EntryId, duplicates: List<EntryId>) {
        val provider = providersByType[entry.contentType] ?: return
        promptForCategories(entry, provider.addToGroup(entry, duplicates))
    }

    internal suspend fun fileAddCategories(entry: EntryId, categoryIds: List<Long>) {
        providersByType[entry.contentType]?.applyAddCategories(entry, categoryIds)
    }

    private fun promptForCategories(entry: EntryId, result: AddFavoriteResult) {
        if (result is AddFavoriteResult.NeedsCategoryChoice) {
            openDialog(RecentsDialog.ChangeCategory(entry, result.initialSelection))
        }
    }

    /** Clears the history of every content type on screen, which is why it is one confirmation. */
    fun clearHistory() {
        activeProviders().forEach { it.clearHistory() }
    }

    /**
     * Updates every library on screen, answering whether anything actually started. Mapped before it is
     * reduced, so one type already running cannot short-circuit the other type's start.
     */
    fun refresh(): Boolean = activeProviders().map { it.refresh() }.any { it }

    /** The details screen for a row, resolved by the provider that owns the entry. */
    suspend fun detailsScreen(entry: EntryId): Screen? =
        providersByType[entry.contentType]?.detailsScreen(entry)

    private fun dispatchAndClear(action: (RecentsProvider) -> Unit) {
        activeProviders().forEach(action)
        clearSelection()
    }

    private fun activeProviders(): List<RecentsProvider> =
        activeIndices(contentType.value).map { providers[it] }

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
