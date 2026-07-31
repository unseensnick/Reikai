package reikai.presentation.library

import android.app.Application
import androidx.compose.ui.util.fastAll
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.library.LibraryScreenModel
import eu.kanade.tachiyomi.util.system.isReleaseBuildType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import reikai.domain.entry.EntryId
import reikai.domain.library.CATEGORY_SORT_CUSTOMIZED
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetSortModeForCategory
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.CategoryUpdate
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.injectLazy

/**
 * Adapts the live Mihon [LibraryScreenModel] to the neutral [LibraryBehavior]. The model stays live and
 * upstream-tracked (never made to implement a Reikai interface); this maps its state into the neutral
 * [LibraryScreenState] and forwards each neutral action to the model's own methods. Symmetric with
 * [NovelLibraryAdapter], so one shared library tab drives both content types through this seam.
 */
class MangaLibraryAdapter(
    private val model: LibraryScreenModel,
) : LibraryProvider {

    // Lazy, so constructing the adapter in a composable never touches the DI container.
    private val getCategories: GetCategories by injectLazy()
    private val context: Application by injectLazy()
    private val libraryPreferences: LibraryPreferences by injectLazy()
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences by injectLazy()
    private val setSortModeForCategory: SetSortModeForCategory by injectLazy()
    private val categoryRepository: CategoryRepository by injectLazy()
    private val trackerManager: TrackerManager by injectLazy()
    private val sourceManager: SourceManager by injectLazy()

    override val contentType = ContentType.MANGA

    // `by lazy` for the same reason as the injections above: nothing here is resolved until a sheet asks.
    override val settings: LibrarySettingsBinding by lazy {
        LibrarySettingsBinding(
            filterAxes = libraryPreferences.autoUpdateMangaRestrictions.changes()
                .map(::filterAxes)
                .stateIn(
                    model.screenModelScope,
                    SharingStarted.Eagerly,
                    filterAxes(libraryPreferences.autoUpdateMangaRestrictions.get()),
                ),
            trackerFilter = libraryPreferences::filterTracking,
            categoryFilter = LibraryCategoryFilter(
                enabled = reikaiLibraryPreferences.filterCategories,
                included = reikaiLibraryPreferences.filterCategoriesInclude,
                excluded = reikaiLibraryPreferences.filterCategoriesExclude,
            ),
            categories = combine(
                getCategories.subscribe(),
                reikaiLibraryPreferences.categorySortOrder.changes(),
            ) { categories, sortOrder ->
                reikaiSortCategories(categories.sortedBy { it.order }, sortOrder)
            }.stateIn(model.screenModelScope, SharingStarted.WhileSubscribed(), emptyList()),
            groupMode = reikaiLibraryPreferences.groupLibraryBy,
            globalSort = libraryPreferences.sortingMode.changes()
                .stateIn(model.screenModelScope, SharingStarted.Eagerly, libraryPreferences.sortingMode.get()),
            setSort = { categoryId, type, direction ->
                model.screenModelScope.launchIO { setSortModeForCategory.await(categoryId, type, direction) }
            },
            resetSort = { categoryId ->
                model.screenModelScope.launchIO {
                    val category = categoryRepository.get(categoryId) ?: return@launchIO
                    categoryRepository.updatePartial(
                        CategoryUpdate(id = categoryId, flags = category.flags and CATEGORY_SORT_CUSTOMIZED.inv()),
                    )
                }
            },
            showLocalBadge = true,
            mergeSourceIcons = reikaiLibraryPreferences.showMergeSourceIcons,
        )
    }

    private fun filterAxes(updateRestrictions: Set<String>) = buildList {
        add(LibraryFilterAxis(MR.strings.label_downloaded, libraryPreferences.filterDownloaded, true))
        add(LibraryFilterAxis(MR.strings.action_filter_unread, libraryPreferences.filterUnread))
        add(LibraryFilterAxis(MR.strings.label_started, libraryPreferences.filterStarted))
        add(LibraryFilterAxis(MR.strings.action_filter_bookmarked, libraryPreferences.filterBookmarked))
        add(LibraryFilterAxis(MR.strings.completed, libraryPreferences.filterCompleted))
        // Upstream keeps custom intervals out of stable, so this axis is debug-only and follows the
        // restriction that produces it. Novels have no equivalent and simply omit it.
        if (!isReleaseBuildType && LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in updateRestrictions) {
            add(LibraryFilterAxis(MR.strings.action_filter_interval_custom, libraryPreferences.filterIntervalCustom))
        }
        add(LibraryFilterAxis(MR.strings.lewd, reikaiLibraryPreferences.filterLewd))
    }

    override val state: StateFlow<LibraryScreenState> =
        model.state
            .map { it.toNeutral() }
            .stateIn(model.screenModelScope, SharingStarted.Eagerly, model.state.value.toNeutral())

    // The split point: filtered but pre-grouping, pre-sort (LibraryData.favorites). distinctUntilChanged
    // because the state re-emits for grouping/badge changes the row list is upstream of.
    override val rows: Flow<List<LibraryItem>> =
        model.state.map { it.libraryData.favorites }.distinctUntilChanged()

    override fun trackerMeans(): Map<Long, Double> {
        val data = model.state.value.libraryData
        val trackers = trackerManager.getAll(data.loggedInTrackerIds).associateBy { it.id }
        return mangaTrackerMeans(data.favorites, data.tracksMap, trackers)
    }

    override fun dynamicGroupingFeed(groupType: Int): DynamicGroupingFeed {
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

    private fun LibraryScreenModel.State.toNeutral() = LibraryScreenState(
        categories = displayedCategories,
        isLoading = isLoading,
        isLibraryEmpty = isLibraryEmpty,
        searchQuery = searchQuery,
        hasActiveFilters = hasActiveFilters,
        activeCategoryIndex = activeCategoryIndex,
        showContinueButton = showMangaContinueButton,
        itemsForCategory = this::getItemsForCategory,
        itemCountForCategory = this::getItemCountForCategory,
    )

    override fun search(query: String?) {
        model.search(query)
    }

    override fun refresh(category: Category?) = LibraryUpdateJob.startNow(context, category)

    override fun randomEntry(categoryId: Long?): EntryId? {
        val state = model.state.value
        val item = if (categoryId == null) {
            state.libraryData.favorites.randomOrNull()
        } else {
            state.getItemsForCategoryId(categoryId).randomOrNull()
        }
        return item?.entryId
    }

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
    override fun canDownload(entries: Set<EntryId>) =
        model.state.value.mangaFor(entries.ownIds()).fastAll { !it.isLocal() }

    override fun groupedSourceCount(entries: Set<EntryId>): Int {
        val ids = entries.ownIds()
        val state = model.state.value
        return if (state.containsMerged(ids)) state.memberIdsFor(ids).size else 0
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
