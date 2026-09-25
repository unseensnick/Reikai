package reikai.presentation.library

import android.content.Context
import androidx.compose.ui.util.fastAll
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.library.LibraryViewModel
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.groupedSourceIdsOf
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.source.local.isLocal
import kotlin.time.Duration.Companion.seconds

/**
 * Adapts the live Mihon [LibraryViewModel] to the neutral [LibraryBehavior]. The model stays live and
 * upstream-tracked (never made to implement a Reikai interface); this maps its state into the neutral
 * [LibraryScreenState] and forwards each neutral action to the model's own methods. Symmetric with
 * [NovelLibraryAdapter], so one shared library tab drives both content types through this seam.
 */
@AssistedInject
class MangaLibraryAdapter(
    // Assisted: the model belongs to the tab that is composing, so only the call site has it.
    @Assisted private val model: LibraryViewModel,
    private val getCategories: GetCategories,
    private val context: Context,
    private val libraryPreferences: LibraryPreferences,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val trackerManager: TrackerManager,
    private val sourceManager: SourceManager,
) : LibraryProvider {

    @AssistedFactory
    fun interface Factory {
        fun create(model: LibraryViewModel): MangaLibraryAdapter
    }

    override val contentType = ContentType.MANGA

    // `by lazy` because these flows start on the model's scope, which should not happen until a sheet asks.
    override val settings: LibraryProviderSettings by lazy {
        LibraryProviderSettings(
            filterAxes = libraryPreferences.autoUpdateMangaRestrictions.changes()
                .map(::filterAxes)
                .stateIn(
                    model.viewModelScope,
                    SharingStarted.Eagerly,
                    filterAxes(libraryPreferences.autoUpdateMangaRestrictions.get()),
                ),
            categories = combine(
                getCategories.subscribe(),
                reikaiLibraryPreferences.categorySortOrder.changes(),
            ) { categories, sortOrder ->
                reikaiSortCategories(categories.sortedBy { it.order }, sortOrder)
            }.stateIn(model.viewModelScope, SharingStarted.WhileSubscribed(), emptyList()),
            showLocalBadge = true,
        )
    }

    private fun filterAxes(updateRestrictions: Set<String>) =
        libraryFilterAxes(libraryPreferences, reikaiLibraryPreferences, updateRestrictions)

    // Shared while subscribed, not eagerly: an eager share here is a permanent subscriber on the model,
    // which would hold its own WhileSubscribed window open for the model's whole life and make its
    // conversion buy nothing. The tab collects both adapters at once, so the inactive content type
    // still stays warm for an instant chip swap.
    override val state: StateFlow<LibraryScreenState> =
        model.state
            .map { it.toNeutral() }
            .stateIn(
                model.viewModelScope,
                SharingStarted.WhileSubscribed(5.seconds),
                model.state.value.toNeutral(),
            )

    // The split point: filtered but pre-grouping, pre-sort (LibraryData.favorites). distinctUntilChanged
    // because the state re-emits for grouping/badge changes the row list is upstream of.
    override val rows: Flow<List<LibraryItem>> =
        model.state.map { it.libraryData.favorites }.distinctUntilChanged()

    override fun trackerMeans(): Map<Long, Double> {
        val data = model.state.value.libraryData
        val trackers = trackerManager.getAll(data.loggedInTrackerIds).associateBy { it.id }
        val membersByRow = data.favorites.associate { it.id to it.relatedMangaIds.ifEmpty { listOf(it.id) } }
        return libraryTrackerMeans(membersByRow, data.tracksMap, trackers)
    }

    override suspend fun dynamicGroupingFeed(groupType: Int): DynamicGroupingFeed {
        val data = model.state.value.libraryData
        return mangaDynamicGroupingFeed(
            favorites = data.favorites,
            tracksMap = data.tracksMap,
            loggedInTrackerIds = data.loggedInTrackerIds,
            groupType = groupType,
            sourceManager = sourceManager,
            trackerManager = trackerManager,
            context = context,
        )
    }

    override fun overlaid(item: LibraryItem): LibraryItem = model.state.value.withOverlay(item)

    private fun LibraryViewModel.State.toNeutral() = LibraryScreenState(
        isLoading = isLoading,
        isLibraryEmpty = isLibraryEmpty,
        searchQuery = searchQuery,
        hasActiveFilters = hasActiveFilters,
        activeCategoryIndex = activeCategoryIndex,
        showContinueButton = showMangaContinueButton,
        overlayKey = libraryData.customInfo,
    )

    override fun search(query: String?) {
        model.search(query)
    }

    override fun refresh(category: Category?) = LibraryUpdateJob.startNow(context.workManager, category)

    // Each verb takes the neutral selection and hands the model only the raw ids of its own content
    // type, so a mixed selection never reaches a provider that cannot act on it.
    private fun Set<EntryId>.ownIds() = filterIsInstance<EntryId.Manga>().map { it.rawId }

    override fun markReadSelection(entries: Set<EntryId>, read: Boolean) {
        model.markReadSelection(entries.ownIds(), read)
    }
    override fun performDownloadAction(entries: Set<EntryId>, action: DownloadAction) {
        model.performDownloadAction(entries.ownIds(), action)
    }
    override fun mergeSelection(entries: Set<EntryId>) {
        model.mergeSelection(entries.ownIds())
    }
    override fun unmergeSelection(entries: Set<EntryId>) {
        model.unmergeSelection(entries.ownIds())
    }

    // The model expands merge groups itself for categories; delete expands only on request, and wants the
    // manga rather than their ids, so resolving from state here saves it a DB round-trip.
    override fun setCategories(
        entries: Set<EntryId>,
        addCategories: List<Long>,
        removeCategories: List<Long>,
    ) {
        model.setMangaCategories(model.state.value.mangaFor(entries.ownIds()), addCategories, removeCategories)
    }

    override fun deleteEntries(
        entries: Set<EntryId>,
        deleteFromLibrary: Boolean,
        deleteDownloads: Boolean,
        removeGroupedSources: Boolean,
    ) {
        model.removeMangas(
            model.state.value.mangaFor(entries.ownIds()),
            deleteFromLibrary,
            deleteDownloads,
            removeGroupedSources,
        )
    }

    override fun containsMerged(entries: Set<EntryId>) =
        model.state.value.containsMerged(entries.ownIds())

    // Non-empty matters under All (the engine shows Download if ANY provider can act); the
    // all-non-local rule is upstream's (Download hides when any selected manga is local).
    override fun canDownload(entries: Set<EntryId>) =
        model.state.value.mangaFor(entries.ownIds()).let { it.isNotEmpty() && it.fastAll { m -> !m.isLocal() } }

    override fun groupedSourceCount(entries: Set<EntryId>): Int {
        val state = model.state.value
        return groupedSourceIdsOf(entries.ownIds()) { state.memberIdsFor(listOf(it)) }.size
    }

    override fun containsLocal(entries: Set<EntryId>) =
        model.state.value.mangaFor(entries.ownIds()).any { it.isLocal() }

    override suspend fun assignableCategories() =
        getCategories.await().filterNot { it.isSystemCategory }

    override suspend fun categoryIdsFor(entries: Set<EntryId>): List<Set<Long>> =
        model.state.value.memberIdsFor(entries.ownIds())
            .map { id -> getCategories.await(id).mapTo(mutableSetOf()) { it.id } }

    override fun updateActiveCategoryIndex(index: Int) {
        model.updateActiveCategoryIndex(index)
    }
}
