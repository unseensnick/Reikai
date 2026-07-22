package eu.kanade.tachiyomi.ui.library

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import dev.icerock.moko.resources.StringResource
import eu.kanade.core.preference.PreferenceMutableState
import eu.kanade.core.preference.asState
import eu.kanade.core.util.fastFilterNot
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.presentation.library.components.LibraryToolbarTitle
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import eu.kanade.tachiyomi.util.removeCovers
import exh.search.SearchEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import mihon.core.common.utils.mutate
import reikai.domain.category.categoryDiff
import reikai.domain.category.categoryFilterActive
import reikai.domain.category.isHidden
import reikai.domain.category.matchesCategoryFilter
import reikai.domain.library.ContentType
import reikai.domain.library.LibrarySortFields
import reikai.domain.library.LibrarySortMode
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.librarySortComparator
import reikai.domain.library.sortForCategory
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.manga.PropagateTrackerLinks
import reikai.domain.merge.ChapterMatchKeyRepository
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.ReconcileChapterMatchKeys
import reikai.presentation.library.DynItem
import reikai.presentation.library.LibraryDynamicGrouping
import reikai.presentation.library.LibraryGroup
import reikai.presentation.library.LibraryTrackingStatusOrder
import reikai.presentation.library.MangaMergeCollapse
import reikai.presentation.library.ReikaiDynamicCategory
import reikai.presentation.library.ReikaiLibraryState
import reikai.presentation.library.reikaiSortCategories
import reikai.util.isLewd
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.chapter.interactor.GetBookmarkedChaptersByMangaId
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.GetSearchTags
import tachiyomi.domain.manga.interactor.GetSearchTitles
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.manga.model.withCustomInfo
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.time.Duration.Companion.seconds
import tachiyomi.domain.source.model.Source as DomainSource

class LibraryScreenModel(
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    // RK: per-entry custom title/cover overrides, overlaid on the displayed rows (display-only)
    private val getCustomMangaInfo: GetCustomMangaInfo = Injekt.get(),
    // RK: resolve merged-away group members by id, so a bulk action reaches every source of a merge
    //     group and not just the collapsed primary
    private val getManga: GetManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
    // RK: gallery tags + alt-titles for the library tag-search engine
    private val getSearchTags: GetSearchTags = Injekt.get(),
    private val getSearchTitles: GetSearchTitles = Injekt.get(),
    private val getTracksPerManga: GetTracksPerManga = Injekt.get(),
    private val getNextChapters: GetNextChapters = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val getBookmarkedChaptersByMangaId: GetBookmarkedChaptersByMangaId = Injekt.get(),
    private val setReadStatus: SetReadStatus = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val preferences: BasePreferences = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val downloadCache: DownloadCache = Injekt.get(),
    private val trackerManager: TrackerManager = Injekt.get(),
    // RK -->
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences = Injekt.get(),
    private val mergeManager: MangaMergeManager = Injekt.get(),
    private val mergeGroupRepository: MergeGroupRepository = Injekt.get(),
    private val propagateTrackerLinks: PropagateTrackerLinks = Injekt.get(),
    private val chapterMatchKeyRepository: ChapterMatchKeyRepository = Injekt.get(),
    private val mergedChapterProvider: MergedChapterProvider = Injekt.get(),
    private val reconcileChapterMatchKeys: ReconcileChapterMatchKeys = Injekt.get(),
    // RK <--
) : StateScreenModel<LibraryScreenModel.State>(State()) {

    // RK: parses a typed query into structured tag components (cached); used by the library
    // tag-search for adult/metadata sources.
    private val searchEngine = SearchEngine()

    init {
        mutableState.update { state ->
            state.copy(activeCategoryIndex = libraryPreferences.lastUsedCategory.get())
        }
        // RK: a newly grouped entry's chapters have no cross-source identities yet, so the deduplicated
        //     unread count would be wrong until something wrote them. Reconciling off the membership
        //     flow covers every merge and unmerge from one place, instead of hooking each action, and
        //     costs one indexed query when nothing changed.
        screenModelScope.launchIO {
            mergeGroupRepository.getAllMembershipsAsFlow(ContentType.MANGA)
                .distinctUntilChanged()
                .collectLatest { reconcileChapterMatchKeys.await() }
        }

        screenModelScope.launchIO {
            combine(
                state.map { it.searchQuery }.distinctUntilChanged().debounce(0.25.seconds),
                getCategories.subscribe(),
                // RK: the custom-info overlay rides with favorites (combine caps at 5 sources) but is
                //     NOT applied here: search/filter/sort below all read the raw favorites. It is
                //     carried into LibraryData and applied only at the display read (see State).
                combine(getFavoritesFlow(), getCustomMangaInfo.subscribeAll(), ::Pair),
                combine(getTracksPerManga.subscribe(), getTrackingFiltersFlow(), ::Pair),
                getLibraryItemPreferencesFlow(),
            ) { searchQuery, categories, (favorites, customInfo), (tracksMap, trackingFilters), itemPreferences ->
                val showSystemCategory = favorites.any { it.libraryManga.categories.contains(0) }
                val filteredFavorites = favorites
                    .applyFilters(tracksMap, trackingFilters, itemPreferences)
                    // RK: parse the query once, then filter; metadata-source entries match via the
                    //     structured tag grammar, everything else via plain text (see LibraryItem).
                    .let { items ->
                        if (searchQuery == null) {
                            items
                        } else {
                            val parsedQuery = searchEngine.parseQuery(searchQuery)
                            items.filter { m -> m.matches(searchQuery, parsedQuery, sourceManager) }
                        }
                    }

                LibraryData(
                    isInitialized = true,
                    showSystemCategory = showSystemCategory,
                    categories = categories,
                    favorites = filteredFavorites,
                    tracksMap = tracksMap,
                    loggedInTrackerIds = trackingFilters.keys,
                    // RK: display-only overrides, keyed by real manga id; applied at the display read.
                    customInfo = customInfo.associateBy { it.mangaId },
                )
            }
                .distinctUntilChanged()
                .collectLatest { libraryData ->
                    mutableState.update { state ->
                        state.copy(libraryData = libraryData)
                    }
                }
        }

        screenModelScope.launchIO {
            combine(
                state.dropWhile { !it.libraryData.isInitialized },
                // RK: re-run the sort when the GLOBAL sort changes; non-overridden categories follow it
                //     via the CUSTOMIZED override bit, so a global change re-sorts them (applySort reads
                //     sortingMode fresh). Pairing it into the distinct key is what re-fires the pipeline.
                libraryPreferences.sortingMode.changes(),
            ) { s, globalSort -> s to globalSort }
                // RK --> branch on the Reikai grouping mode; dynamic grouping and category
                // order replace Mihon's plain category bucketing. The distinct key includes
                // only the grouping-relevant Reikai fields so badge/hopper changes don't re-group.
                .map { (it, globalSort) ->
                    Triple(
                        it.libraryData,
                        it.reikai.groupingInputs(),
                        // drop categories an active filter/search emptied, unless the user keeps them
                        (it.hasActiveFilters || it.searchQuery != null) &&
                            !it.reikai.showEmptyCategoriesWhileFiltering,
                    ) to globalSort
                }
                .distinctUntilChanged()
                .map { (inputs, _) ->
                    val (data, grouping, dropEmptyWhileFiltering) = inputs
                    val grouped = if (grouping.groupLibraryBy == LibraryGroup.BY_DEFAULT) {
                        data.favorites
                            .applyGrouping(data.categories, data.showSystemCategory, grouping.showHiddenCategories)
                            .reorderReikaiCategories(grouping.categorySortOrder)
                    } else {
                        buildReikaiDynamicGrouping(data, grouping)
                    }
                    val sorted = grouped.applySort(data.favoritesById, data.tracksMap, data.loggedInTrackerIds)
                    if (dropEmptyWhileFiltering) sorted.filterValues { it.isNotEmpty() } else sorted
                }
                // RK <--
                .collectLatest {
                    mutableState.update { state ->
                        state.copy(
                            isLoading = false,
                            // RK: store as an ordered list so category reorders aren't deduped away
                            groupedFavorites = it.toList(),
                        )
                    }
                }
        }

        combine(
            libraryPreferences.categoryTabs.changes(),
            libraryPreferences.categoryNumberOfItems.changes(),
            libraryPreferences.showContinueReadingButton.changes(),
        ) { a, b, c -> arrayOf(a, b, c) }
            .onEach { (showCategoryTabs, showMangaCount, showMangaContinueButton) ->
                mutableState.update { state ->
                    state.copy(
                        showCategoryTabs = showCategoryTabs,
                        showMangaCount = showMangaCount,
                        showMangaContinueButton = showMangaContinueButton,
                    )
                }
            }
            .launchIn(screenModelScope)

        combine(
            getLibraryItemPreferencesFlow(),
            getTrackingFiltersFlow(),
        ) { prefs, trackFilters ->
            listOf(
                prefs.filterDownloaded,
                prefs.filterUnread,
                prefs.filterStarted,
                prefs.filterBookmarked,
                prefs.filterCompleted,
                prefs.filterIntervalCustom,
                // RK --> lewd counts as an active filter dim
                prefs.filterLewd,
                // RK <--
                *trackFilters.values.toTypedArray(),
            )
                .any { it != TriState.DISABLED } ||
                // RK --> include/exclude category filter is a Boolean dim, not a TriState
                categoryFilterActive(
                    prefs.filterCategories,
                    prefs.filterCategoriesInclude,
                    prefs.filterCategoriesExclude,
                )
            // RK <--
        }
            .distinctUntilChanged()
            .onEach {
                mutableState.update { state ->
                    state.copy(hasActiveFilters = it)
                }
            }
            .launchIn(screenModelScope)

        // RK -->
        getReikaiLibraryStateFlow()
            .distinctUntilChanged()
            .onEach { reikai -> mutableState.update { it.copy(reikai = reikai) } }
            .launchIn(screenModelScope)
        // RK <--
    }

    // RK -->
    @Suppress("UNCHECKED_CAST")
    private fun getReikaiLibraryStateFlow(): Flow<ReikaiLibraryState> {
        return combine(
            reikaiLibraryPreferences.groupLibraryBy.changes(),
            reikaiLibraryPreferences.collapsedCategories.changes(),
            reikaiLibraryPreferences.collapsedDynamicCategories.changes(),
            reikaiLibraryPreferences.collapsedDynamicAtBottom.changes(),
            reikaiLibraryPreferences.categorySortOrder.changes(),
            reikaiLibraryPreferences.showCategoryInTitle.changes(),
            reikaiLibraryPreferences.showAllCategories.changes(),
            reikaiLibraryPreferences.showEmptyCategoriesWhileFiltering.changes(),
            reikaiLibraryPreferences.hideHopper.changes(),
            reikaiLibraryPreferences.autohideHopper.changes(),
            reikaiLibraryPreferences.hopperGravity.changes(),
            reikaiLibraryPreferences.hopperLongPressAction.changes(),
            reikaiLibraryPreferences.showHiddenCategories.changes(),
            reikaiLibraryPreferences.trackUpdateErrors.changes(),
            reikaiLibraryPreferences.trackNovelUpdateErrors.changes(),
        ) {
            ReikaiLibraryState(
                groupLibraryBy = it[0] as Int,
                collapsedCategories = it[1] as Set<String>,
                collapsedDynamicCategories = it[2] as Set<String>,
                collapsedDynamicAtBottom = it[3] as Boolean,
                categorySortOrder = it[4] as Int,
                showCategoryInTitle = it[5] as Boolean,
                showAllCategories = it[6] as Boolean,
                showEmptyCategoriesWhileFiltering = it[7] as Boolean,
                hideHopper = it[8] as Boolean,
                autohideHopper = it[9] as Boolean,
                hopperGravity = it[10] as Int,
                hopperLongPressAction = it[11] as Int,
                showHiddenCategories = it[12] as Boolean,
                trackUpdateErrors = it[13] as Boolean,
                trackNovelUpdateErrors = it[14] as Boolean,
            )
        }
    }

    fun setGroupLibraryBy(value: Int) {
        reikaiLibraryPreferences.groupLibraryBy.set(value)
    }

    fun setHopperGravity(value: Int) {
        reikaiLibraryPreferences.hopperGravity.set(value)
    }

    fun setCategorySortOrder(value: Int) {
        reikaiLibraryPreferences.categorySortOrder.set(value)
    }

    fun toggleDefaultCategoryCollapse(headerKey: String) {
        val pref = reikaiLibraryPreferences.collapsedCategories
        val current = pref.get()
        pref.set(if (headerKey in current) current - headerKey else current + headerKey)
    }

    fun toggleDynamicCategoryCollapse(headerKey: String) {
        val pref = reikaiLibraryPreferences.collapsedDynamicCategories
        val current = pref.get()
        pref.set(if (headerKey in current) current - headerKey else current + headerKey)
    }

    fun expandOrCollapseAllCategories(headerKeys: Set<String>) {
        val pref = reikaiLibraryPreferences.collapsedCategories
        val current = pref.get()
        pref.set(if (current.containsAll(headerKeys)) current - headerKeys else current + headerKeys)
    }

    /**
     * Toggle every currently-displayed category collapsed/expanded (hopper long-press). Handles
     * both real categories (collapsedCategories) and dynamic groups (collapsedDynamicCategories).
     */
    fun toggleAllCategoriesCollapsed(categories: List<Category>) {
        val defaultKeys = categories.filterNot { ReikaiDynamicCategory.isDynamic(it) }
            .map { it.id.toString() }.toSet()
        val dynamicKeys = categories.filter { ReikaiDynamicCategory.isDynamic(it) }
            .map { ReikaiDynamicCategory.headerKey(it) }.toSet()
        val defaultPref = reikaiLibraryPreferences.collapsedCategories
        val dynamicPref = reikaiLibraryPreferences.collapsedDynamicCategories
        val allCollapsed = defaultPref.get().containsAll(defaultKeys) && dynamicPref.get().containsAll(dynamicKeys)
        if (allCollapsed) {
            defaultPref.set(defaultPref.get() - defaultKeys)
            dynamicPref.set(dynamicPref.get() - dynamicKeys)
        } else {
            defaultPref.set(defaultPref.get() + defaultKeys)
            dynamicPref.set(dynamicPref.get() + dynamicKeys)
        }
    }

    private data class GroupingInputs(
        val groupLibraryBy: Int,
        val categorySortOrder: Int,
        val collapsedDynamicCategories: Set<String>,
        val collapsedDynamicAtBottom: Boolean,
        val showHiddenCategories: Boolean,
    )

    private fun ReikaiLibraryState.groupingInputs() = GroupingInputs(
        groupLibraryBy = groupLibraryBy,
        categorySortOrder = categorySortOrder,
        collapsedDynamicCategories = collapsedDynamicCategories,
        collapsedDynamicAtBottom = collapsedDynamicAtBottom,
        showHiddenCategories = showHiddenCategories,
    )

    /** Order the category buckets (0 = manual/DB order, 1 = A->Z, 2 = Z->A; system pinned on top). */
    private fun Map<Category, List<Long>>.reorderReikaiCategories(categorySortOrder: Int): Map<Category, List<Long>> {
        if (categorySortOrder == 0 || isEmpty()) return this
        return reikaiSortCategories(keys.toList(), categorySortOrder).associateWith { getValue(it) }
    }

    /** Bucket the library into synthetic dynamic categories, resolving per-manga metadata. */
    private fun buildReikaiDynamicGrouping(data: LibraryData, grouping: GroupingInputs): Map<Category, List<Long>> {
        val context = Injekt.get<Application>()
        val groupType = grouping.groupLibraryBy
        val library = data.favorites.map { it.libraryManga }

        val sourceMeta = if (groupType == LibraryGroup.BY_SOURCE) {
            library.associate { lm ->
                val source = sourceManager.getOrStub(lm.manga.source)
                lm.manga.id to (source.name to source.id.toString())
            }
        } else {
            emptyMap()
        }

        val languageCodes = if (groupType == LibraryGroup.BY_LANGUAGE) {
            library.mapNotNull { lm ->
                val lang = sourceManager.getOrStub(lm.manga.source).lang.takeUnless { it.isBlank() }
                    ?: return@mapNotNull null
                lm.manga.id to lang
            }.toMap()
        } else {
            emptyMap()
        }

        val statusNames = if (groupType == LibraryGroup.BY_STATUS) {
            library.associate { lm -> lm.manga.id to context.stringResource(mapMangaStatus(lm.manga.status)) }
        } else {
            emptyMap()
        }

        val trackStatuses = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
            data.favorites.mapNotNull { item ->
                val mangaId = item.libraryManga.manga.id
                // RK: union tracks across the merged group (relatedMangaIds), so a status bound on any
                // grouped source groups the row, matching the tracker filter/sort and the novel library.
                val groupIds = item.relatedMangaIds.ifEmpty { listOf(mangaId) }
                val track = groupIds.flatMap { data.tracksMap[it].orEmpty() }
                    .firstOrNull { it.trackerId in data.loggedInTrackerIds }
                    ?: return@mapNotNull null
                val statusRes = trackerManager.get(track.trackerId)?.getStatus(track.status)
                    ?: return@mapNotNull null
                mangaId to context.stringResource(statusRes)
            }.toMap()
        } else {
            emptyMap()
        }

        // RK: order the track-status buckets by each tracker's own status list (Reading first, Dropped
        // last) instead of alphabetically; identity for other groupings, which the kernel ignores anyway.
        val trackingStatusOrder: (String) -> String = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
            LibraryTrackingStatusOrder.build(
                data.loggedInTrackerIds.mapNotNull { trackerManager.get(it) },
            ) { context.stringResource(it) }
        } else {
            { it }
        }

        return LibraryDynamicGrouping.build(
            items = library.map { DynItem(it.manga.id, it.manga.genre, it.manga.author, it.manga.artist) },
            groupType = groupType,
            inheritedSortFlag = libraryPreferences.sortingMode.get().flag,
            collapsedDynamicCategories = grouping.collapsedDynamicCategories,
            collapsedDynamicAtBottom = grouping.collapsedDynamicAtBottom,
            unknownLabel = context.stringResource(MR.strings.unknown),
            notTrackedLabel = context.stringResource(MR.strings.not_tracked),
            ungroupedLabel = context.stringResource(MR.strings.group_ungrouped),
            categorySortOrder = grouping.categorySortOrder,
            sourceMeta = sourceMeta,
            trackStatuses = trackStatuses,
            languageCodes = languageCodes,
            statusNames = statusNames,
            languageDisplay = { code -> displayLanguage(code) },
            trackingStatusOrder = trackingStatusOrder,
        )
    }

    private fun mapMangaStatus(status: Long): StringResource = when (status.toInt()) {
        SManga.ONGOING -> MR.strings.ongoing
        SManga.COMPLETED -> MR.strings.completed
        SManga.LICENSED -> MR.strings.licensed
        SManga.PUBLISHING_FINISHED -> MR.strings.publishing_finished
        SManga.CANCELLED -> MR.strings.cancelled
        SManga.ON_HIATUS -> MR.strings.on_hiatus
        else -> MR.strings.unknown
    }

    private fun displayLanguage(code: String): String =
        java.util.Locale.forLanguageTag(code).displayName.ifBlank { code }
    // RK <--

    private fun List<LibraryItem>.applyFilters(
        trackMap: Map<Long, List<Track>>,
        trackingFilter: Map<Long, TriState>,
        preferences: ItemPreferences,
    ): List<LibraryItem> {
        val downloadedOnly = preferences.globalFilterDownloaded
        val skipOutsideReleasePeriod = preferences.skipOutsideReleasePeriod
        val filterDownloaded = if (downloadedOnly) TriState.ENABLED_IS else preferences.filterDownloaded
        val filterUnread = preferences.filterUnread
        val filterStarted = preferences.filterStarted
        val filterBookmarked = preferences.filterBookmarked
        val filterCompleted = preferences.filterCompleted
        val filterIntervalCustom = preferences.filterIntervalCustom

        val isNotLoggedInAnyTrack = trackingFilter.isEmpty()

        val excludedTracks = trackingFilter.mapNotNull { if (it.value == TriState.ENABLED_NOT) it.key else null }
        val includedTracks = trackingFilter.mapNotNull { if (it.value == TriState.ENABLED_IS) it.key else null }
        val trackFiltersIsIgnored = includedTracks.isEmpty() && excludedTracks.isEmpty()

        val filterFnDownloaded: (LibraryItem) -> Boolean = {
            applyFilter(filterDownloaded) { it.isLocal || it.downloadCount > 0 }
        }

        val filterFnUnread: (LibraryItem) -> Boolean = {
            // RK: LibraryItem.unreadCount, not the LibraryManga's, so a merged entry filters on its
            //     deduplicated group count. Identical for an unmerged entry.
            applyFilter(filterUnread) { it.unreadCount > 0 }
        }

        val filterFnStarted: (LibraryItem) -> Boolean = {
            applyFilter(filterStarted) { it.libraryManga.hasStarted }
        }

        val filterFnBookmarked: (LibraryItem) -> Boolean = {
            applyFilter(filterBookmarked) { it.libraryManga.hasBookmarks }
        }

        val filterFnCompleted: (LibraryItem) -> Boolean = {
            applyFilter(filterCompleted) { it.libraryManga.manga.status.toInt() == SManga.COMPLETED }
        }

        val filterFnIntervalCustom: (LibraryItem) -> Boolean = {
            if (skipOutsideReleasePeriod) {
                applyFilter(filterIntervalCustom) { it.libraryManga.manga.fetchInterval < 0 }
            } else {
                true
            }
        }

        val filterFnTracking: (LibraryItem) -> Boolean = tracking@{ item ->
            if (isNotLoggedInAnyTrack || trackFiltersIsIgnored) return@tracking true

            // RK: union tracks across the merged group (relatedMangaIds), so a tracker bound on any
            // grouped source counts toward the filter, matching the novel library. relatedMangaIds is
            // empty for a non-merged entry, so it falls back to the entry's own id.
            val groupIds = item.relatedMangaIds.ifEmpty { listOf(item.id) }
            val mangaTracks = groupIds.flatMap { trackMap[it].orEmpty() }.map { it.trackerId }

            val isExcluded = excludedTracks.isNotEmpty() && mangaTracks.fastAny { it in excludedTracks }
            val isIncluded = includedTracks.isEmpty() || mangaTracks.fastAny { it in includedTracks }

            !isExcluded && isIncluded
        }

        // RK --> net-new Reikai filter dims (lewd + include/exclude category)
        val filterLewd = preferences.filterLewd
        val includeCategories = preferences.filterCategoriesInclude
        val excludeCategories = preferences.filterCategoriesExclude
        val filterCategoriesActive =
            categoryFilterActive(preferences.filterCategories, includeCategories, excludeCategories)

        val filterFnLewd: (LibraryItem) -> Boolean = {
            applyFilter(filterLewd) {
                it.libraryManga.manga.isLewd(sourceManager.getOrStub(it.libraryManga.manga.source).name)
            }
        }

        val filterFnCategories: (LibraryItem) -> Boolean = catFilter@{ item ->
            if (!filterCategoriesActive) return@catFilter true
            matchesCategoryFilter(item.libraryManga.categories, includeCategories, excludeCategories)
        }
        // RK <--

        return fastFilter {
            filterFnDownloaded(it) &&
                filterFnUnread(it) &&
                filterFnStarted(it) &&
                filterFnBookmarked(it) &&
                filterFnCompleted(it) &&
                filterFnIntervalCustom(it) &&
                filterFnTracking(it) &&
                // RK -->
                filterFnLewd(it) &&
                filterFnCategories(it)
            // RK <--
        }
    }

    private fun List<LibraryItem>.applyGrouping(
        categories: List<Category>,
        showSystemCategory: Boolean,
        // RK --> reveal hidden categories (a flags bit) when the user opts in
        showHiddenCategories: Boolean,
        // RK <--
    ): Map<Category, List</* LibraryItem */ Long>> {
        val groupCache = mutableMapOf</* Category */ Long, MutableList</* LibraryItem */ Long>>()
        forEach { item ->
            item.libraryManga.categories.forEach { categoryId ->
                groupCache.getOrPut(categoryId) { mutableListOf() }.add(item.id)
            }
        }
        return categories.filter { showSystemCategory || !it.isSystemCategory }
            // RK: drop hidden categories (a flags bit) unless the user reveals them
            .filter { showHiddenCategories || !it.isHidden }
            .associateWith { groupCache[it.id]?.toList().orEmpty() }
    }

    // RK -->
    // Manga library sort, routed through the shared reikai.domain.library.librarySortComparator so a sort
    // behaviour change is written once for manga and novels. Manga keeps its OWN LibrarySort bit decoder
    // (sortForCategory), because manga and novels store the TrackerMean / Downloaded sorts on swapped bits.
    private fun Map<Category, List</* LibraryItem */ Long>>.applySort(
        favoritesById: Map<Long, LibraryItem>,
        trackMap: Map<Long, List<Track>>,
        loggedInTrackerIds: Set<Long>,
    ): Map<Category, List</* LibraryItem */ Long>> {
        // Score each entry over its whole merged group (relatedMangaIds), deduped by tracker and dropping
        // unrated (<= 0) scores, so a tracker on any grouped source contributes once, matching the novel
        // library. Keyed by the entry's own id; unscored entries are absent and fall back to the -1.0
        // default. Guarding on the mapped scores (not the raw track list) fixes the upstream bug where an
        // all-logged-out track list averaged to NaN and sorted above every real score.
        val trackerScores by lazy {
            val trackerMap = trackerManager.getAll(loggedInTrackerIds).associateBy { it.id }
            buildMap {
                favoritesById.values.forEach { item ->
                    val ids = item.relatedMangaIds.ifEmpty { listOf(item.id) }
                    val scores = ids.flatMap { trackMap[it].orEmpty() }
                        .distinctBy { it.trackerId }
                        .mapNotNull { trackerMap[it.trackerId]?.get10PointScore(it)?.takeIf { s -> s > 0.0 } }
                    if (scores.isNotEmpty()) put(item.id, scores.average())
                }
            }
        }

        val fields = LibrarySortFields<LibraryItem>(
            id = { it.id },
            title = { it.libraryManga.manga.title },
            lastRead = { it.libraryManga.lastRead },
            lastUpdate = { it.libraryManga.manga.lastUpdate },
            // LibraryItem.unreadCount (the deduplicated group count), not the LibraryManga's.
            unreadCount = { it.unreadCount },
            totalChapters = { it.libraryManga.totalChapters },
            latestUpload = { it.libraryManga.latestUpload },
            chapterFetchedAt = { it.libraryManga.chapterFetchedAt },
            dateAdded = { it.libraryManga.manga.dateAdded },
            downloadCount = { it.downloadCount.toLong() },
            trackerMean = { trackerScores[it.id] ?: -1.0 },
        )

        // A category follows the global sort unless it has a per-category override (CUSTOMIZED bit).
        val globalSort = libraryPreferences.sortingMode.get()
        val randomSeed = libraryPreferences.randomSortSeed.get().toLong()
        return mapValues { (key, value) ->
            val sort = sortForCategory(key.flags, globalSort)
            val comparator = librarySortComparator(sort.type.toSortMode(), sort.isAscending, randomSeed, fields)
            value.mapNotNull { favoritesById[it] }.sortedWith(comparator).map { it.id }
        }
    }

    private fun LibrarySort.Type.toSortMode(): LibrarySortMode = when (this) {
        LibrarySort.Type.Alphabetical -> LibrarySortMode.Alphabetical
        LibrarySort.Type.LastRead -> LibrarySortMode.LastRead
        LibrarySort.Type.LastUpdate -> LibrarySortMode.LastUpdate
        LibrarySort.Type.UnreadCount -> LibrarySortMode.UnreadCount
        LibrarySort.Type.TotalChapters -> LibrarySortMode.TotalChapters
        LibrarySort.Type.LatestChapter -> LibrarySortMode.LatestChapter
        LibrarySort.Type.ChapterFetchDate -> LibrarySortMode.ChapterFetchDate
        LibrarySort.Type.DateAdded -> LibrarySortMode.DateAdded
        LibrarySort.Type.TrackerMean -> LibrarySortMode.TrackerMean
        LibrarySort.Type.Downloaded -> LibrarySortMode.Downloaded
        LibrarySort.Type.Random -> LibrarySortMode.Random
    }
    // RK <--

    private fun getLibraryItemPreferencesFlow(): Flow<ItemPreferences> {
        return combine(
            libraryPreferences.downloadBadge.changes(),
            libraryPreferences.unreadBadge.changes(),
            libraryPreferences.localBadge.changes(),
            libraryPreferences.languageBadge.changes(),
            libraryPreferences.autoUpdateMangaRestrictions.changes(),

            preferences.downloadedOnly.changes(),
            libraryPreferences.filterDownloaded.changes(),
            libraryPreferences.filterUnread.changes(),
            libraryPreferences.filterStarted.changes(),
            libraryPreferences.filterBookmarked.changes(),
            libraryPreferences.filterCompleted.changes(),
            libraryPreferences.filterIntervalCustom.changes(),
            // RK --> net-new Reikai filter dims + badge data
            reikaiLibraryPreferences.filterLewd.changes(),
            reikaiLibraryPreferences.filterCategories.changes(),
            reikaiLibraryPreferences.filterCategoriesInclude.changes(),
            reikaiLibraryPreferences.filterCategoriesExclude.changes(),
            reikaiLibraryPreferences.sourceBadge.changes(),
            // RK <--
        ) {
            ItemPreferences(
                downloadBadge = it[0] as Boolean,
                unreadBadge = it[1] as Boolean,
                localBadge = it[2] as Boolean,
                languageBadge = it[3] as Boolean,
                skipOutsideReleasePeriod = LibraryPreferences.MANGA_OUTSIDE_RELEASE_PERIOD in (it[4] as Set<*>),
                globalFilterDownloaded = it[5] as Boolean,
                filterDownloaded = it[6] as TriState,
                filterUnread = it[7] as TriState,
                filterStarted = it[8] as TriState,
                filterBookmarked = it[9] as TriState,
                filterCompleted = it[10] as TriState,
                filterIntervalCustom = it[11] as TriState,
                // RK -->
                filterLewd = it[12] as TriState,
                filterCategories = it[13] as Boolean,
                filterCategoriesInclude = (it[14] as Set<*>).mapNotNull { id ->
                    (id as? String)?.toLongOrNull()
                }.toSet(),
                filterCategoriesExclude = (it[15] as Set<*>).mapNotNull { id ->
                    (id as? String)?.toLongOrNull()
                }.toSet(),
                sourceBadge = it[16] as Boolean,
                // RK <--
            )
        }
    }

    private fun getFavoritesFlow(): Flow<List<LibraryItem>> {
        return combine(
            getLibraryManga.subscribe(),
            getLibraryItemPreferencesFlow(),
            downloadCache.changes,
            // RK: re-collapse when the merge prefs change
            mergePrefsFlow(),
        ) { libraryManga, preferences, _, mergePrefs ->
            // RK: one batch query each for every gallery's EXH tags + alt-titles (empty for
            //     libraries without adult metadata), keyed by manga id for LibraryItem.matches.
            val tagsByManga = getSearchTags.awaitAll().groupBy { it.mangaId }
            val titlesByManga = getSearchTitles.awaitAll().groupBy { it.mangaId }
            val items = libraryManga.map { manga ->
                // RK: resolve the download count once (it walks the download-cache tree); reused for the
                //     field and the badge instead of two identical traversals per manga per emit.
                val downloadCount = downloadManager.getDownloadCount(manga.manga)
                LibraryItem(
                    libraryManga = manga,
                    downloadCount = downloadCount,
                    unreadCount = manga.unreadCount,
                    searchTags = tagsByManga[manga.id],
                    searchTitles = titlesByManga[manga.id],
                    isLocal = manga.manga.isLocal(),
                    badges = LibraryItem.Badges(
                        downloadCount = if (preferences.downloadBadge) {
                            downloadCount
                        } else {
                            0
                        },
                        unreadCount = if (preferences.unreadBadge) {
                            manga.unreadCount
                        } else {
                            0
                        },
                        isLocal = if (preferences.localBadge) {
                            manga.manga.isLocal()
                        } else {
                            false
                        },
                        sourceLanguage = if (preferences.languageBadge) {
                            sourceManager.getOrStub(manga.manga.source).lang
                        } else {
                            ""
                        },
                        // RK: source/extension icon badge data (null when the source badge is off)
                        source = if (preferences.sourceBadge) {
                            sourceManager.getOrStub(manga.manga.source).let { s ->
                                DomainSource(s.id, s.lang, s.name, supportsLatest = false, isStub = s is StubSource)
                            }
                        } else {
                            null
                        },
                    ),
                )
            }
            // RK: collapse persisted merge groups into one entry per group. Returns the RAW items:
            //     search, filter and sort in the outer combine all read these, so the display-only
            //     custom-info overlay is applied later, at the per-category display read (see State).
            MangaMergeCollapse.collapse(
                items = items,
                membership = mergePrefs.membership,
                mergingEnabled = mergePrefs.mergingEnabled,
                showMergeSourceIcons = mergePrefs.showMergeSourceIcons,
                resolveSource = ::resolveMergeSource,
                // RK: read fresh on every emission rather than cached, so finishing a chapter updates
                //     the badge immediately: this flow already re-fires on any chapter change.
                mergedUnreadByGroup = if (mergePrefs.mergingEnabled) {
                    chapterMatchKeyRepository.getMergedUnreadCounts()
                } else {
                    emptyMap()
                },
                showUnreadBadge = preferences.unreadBadge,
                overrideRankings = mergePrefs.overrideRankings,
                preferredSourceIds = mergePrefs.preferredSources,
            )
        }
    }

    // RK -->
    private data class MergePrefs(
        val membership: Map<Long, Long>,
        val mergingEnabled: Boolean,
        val showMergeSourceIcons: Boolean,
        // Per-group source-order overrides and the global preferred-source list, so the collapsed row
        // leads on the user's chosen trunk. A reorder writes these tables/prefs and re-collapses live.
        val overrideRankings: Map<Long, List<Long>>,
        val preferredSources: List<Long>,
    )

    private fun mergePrefsFlow(): Flow<MergePrefs> = combine(
        mergeGroupRepository.getAllMembershipsAsFlow(ContentType.MANGA),
        reikaiLibraryPreferences.seriesMergingEnabled.changes(),
        reikaiLibraryPreferences.showMergeSourceIcons.changes(),
        mergeGroupRepository.getOverrideRankingsAsFlow(ContentType.MANGA),
        reikaiLibraryPreferences.preferredMangaSources.changes(),
    ) { membership, mergingEnabled, showIcons, overrideRankings, preferredSources ->
        MergePrefs(membership, mergingEnabled, showIcons, overrideRankings, preferredSources)
    }

    private fun resolveMergeSource(sourceId: Long): DomainSource {
        val s = sourceManager.getOrStub(sourceId)
        return DomainSource(s.id, s.lang, s.name, supportsLatest = false, isStub = s is StubSource)
    }
    // RK <--

    /**
     * Flow of tracking filter preferences
     *
     * @return map of track id with the filter value
     */
    private fun getTrackingFiltersFlow(): Flow<Map<Long, TriState>> {
        return trackerManager.loggedInTrackersFlow().flatMapLatest { loggedInTrackers ->
            if (loggedInTrackers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val filterFlows = loggedInTrackers.map { tracker ->
                    libraryPreferences.filterTracking(tracker.id.toInt()).changes().map { tracker.id to it }
                }
                combine(filterFlows) { it.toMap() }
            }
        }
    }

    suspend fun getNextUnreadChapter(manga: Manga): Chapter? {
        // RK: resume over the whole merge group, not just the entry's own source. The badge counts a
        //     chapter as read when any source's copy is read, so resolving the next unread from one
        //     source alone could reopen something the badge already considers finished, or find nothing
        //     while the badge still shows unread. The provider returns the same deduplicated list the
        //     details screen shows, and each chapter keeps its own mangaId so the reader opens the right
        //     source. Falls through to the plain per-manga list when the entry is not merged.
        val group = mergedChapterProvider.load(manga)
        return group.chapters.getNextUnread(manga, downloadManager, group.readInOtherSources)
    }

    /**
     * Queues the amount specified of unread chapters from the list of selected manga
     */
    fun performDownloadAction(action: DownloadAction) {
        when (action) {
            DownloadAction.NEXT_1_CHAPTER -> downloadNextChapters(1)
            DownloadAction.NEXT_5_CHAPTERS -> downloadNextChapters(5)
            DownloadAction.NEXT_10_CHAPTERS -> downloadNextChapters(10)
            DownloadAction.NEXT_25_CHAPTERS -> downloadNextChapters(25)
            DownloadAction.UNREAD_CHAPTERS -> downloadNextChapters(null)
            DownloadAction.BOOKMARKED_CHAPTERS -> downloadBookmarkedChapters()
        }
        clearSelection()
    }

    // RK --> a merged cover is one selected row standing for its whole group, so a bulk action has to
    // act on every member, not just the collapsed primary. The members are collapsed out of the
    // library state, so they resolve from the DB by id. Ids are captured by the caller before the
    // selection clears (LibraryTab clears it as soon as the action returns), never inside the
    // coroutine, which would race that and come back empty.
    private suspend fun resolveSelectedGroupManga(memberIds: List<Long>): List<Manga> =
        memberIds.mapNotNull { getManga.await(it) }
    // RK <--

    // RK: downloads deliberately do NOT fan out across a merge group. The grouped sources carry the
    //     same chapters, so downloading every member would fetch each chapter once per source and
    //     waste the storage on near-duplicates. The right target is the group's deduplicated chapter
    //     list (what the details "All" view shows), which the library cannot build without the
    //     aggregation; until it does, this stays on the collapsed primary, which becomes the user's
    //     chosen trunk once the collapse honours the persisted source ranking.
    private fun downloadNextChapters(amount: Int?) {
        val mangas = state.value.selectedManga
        screenModelScope.launchNonCancellable {
            mangas.forEach { manga ->
                val chapters = getNextChapters.await(manga.id)
                    .fastFilterNot { chapter ->
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                manga.title,
                                manga.source,
                            )
                    }
                    .let { if (amount != null) it.take(amount) else it }

                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    private fun downloadBookmarkedChapters() {
        val mangas = state.value.selectedManga
        screenModelScope.launchNonCancellable {
            mangas.forEach { manga ->
                val chapters = getBookmarkedChaptersByMangaId.await(manga.id)
                    .fastFilterNot { chapter ->
                        downloadManager.getQueuedDownloadOrNull(chapter.id) != null ||
                            downloadManager.isChapterDownloaded(
                                chapter.name,
                                chapter.scanlator,
                                chapter.url,
                                manga.title,
                                manga.source,
                            )
                    }
                downloadManager.downloadChapters(manga, chapters)
            }
        }
    }

    /**
     * Marks mangas' chapters read status.
     */
    fun markReadSelection(read: Boolean) {
        // RK: mark every source of a merge group, so a merged series doesn't stay part-read on the
        //     sources that aren't the collapsed primary.
        val memberIds = state.value.selectedMemberIds
        screenModelScope.launchNonCancellable {
            resolveSelectedGroupManga(memberIds).forEach { manga ->
                setReadStatus.await(
                    manga = manga,
                    read = read,
                )
            }
        }
        clearSelection()
    }

    /**
     * Remove the selected manga.
     *
     * @param mangas the list of manga to delete.
     * @param deleteFromLibrary whether to delete manga from library.
     * @param deleteChapters whether to delete downloaded chapters.
     */
    fun removeMangas(
        mangas: List<Manga>,
        deleteFromLibrary: Boolean,
        deleteChapters: Boolean,
        // RK: expand merged covers to every grouped source, so the whole series leaves the library
        //     instead of just the primary. Scopes both the library removal and the download deletion.
        removeGroupedSources: Boolean = false,
    ) {
        // RK: capture the group member ids now, on the caller thread. LibraryTab clears the selection
        //     right after this returns, so reading selectedMemberIds inside the coroutine would race
        //     it and come back empty.
        val memberIds = if (removeGroupedSources) state.value.selectedMemberIds else emptyList()
        screenModelScope.launchNonCancellable {
            // RK: the merged-away group members are collapsed out of the library state, so resolve
            //     them from the DB by id; falls back to the passed-in mangas when not expanding.
            val targets = if (removeGroupedSources) {
                memberIds.mapNotNull { getManga.await(it) }
            } else {
                mangas
            }
            if (deleteFromLibrary) {
                val toDelete = targets.map {
                    it.removeCovers(coverCache)
                    MangaUpdate(
                        favorite = false,
                        id = it.id,
                    )
                }
                updateManga.awaitAll(toDelete)
            }

            if (deleteChapters) {
                targets.forEach { manga ->
                    val source = sourceManager.get(manga.source) as? HttpSource
                    if (source != null) {
                        downloadManager.deleteManga(manga, source)
                    }
                }
            }
        }
    }

    /**
     * Bulk update categories of manga using old and new common categories.
     *
     * @param mangaList the list of manga to move.
     * @param addCategories the categories to add for all mangas.
     * @param removeCategories the categories to remove in all mangas.
     */
    fun setMangaCategories(mangaList: List<Manga>, addCategories: List<Long>, removeCategories: List<Long>) {
        // RK: apply to every source of a merge group, so members can't drift into different
        //     categories and make the entry vanish from a category the user moved it to. Works on
        //     ids, so the merged-away members need no DB round-trip.
        val favoritesById = state.value.libraryData.favoritesById
        val memberIds = mangaList.flatMap { manga ->
            favoritesById[manga.id]?.relatedMangaIds?.ifEmpty { listOf(manga.id) } ?: listOf(manga.id)
        }.distinct()
        screenModelScope.launchNonCancellable {
            memberIds.forEach { mangaId ->
                val categoryIds = getCategories.await(mangaId)
                    .map { it.id }
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .toList()

                setMangaCategories.await(mangaId, categoryIds)
            }
        }
    }

    fun getDisplayMode(): PreferenceMutableState<LibraryDisplayMode> {
        return libraryPreferences.displayMode.asState(screenModelScope)
    }

    fun getColumnsForOrientation(isLandscape: Boolean): PreferenceMutableState<Int> {
        return (if (isLandscape) libraryPreferences.landscapeColumns else libraryPreferences.portraitColumns)
            .asState(screenModelScope)
    }

    fun getRandomLibraryItemForCurrentCategory(): LibraryItem? {
        val state = state.value
        return state.getItemsForCategoryId(state.activeCategory?.id).randomOrNull()
    }

    // RK: a random entry from the whole library (hopper long-press "random, global" action)
    fun getRandomLibraryItem(): LibraryItem? = state.value.libraryData.favorites.randomOrNull()

    // RK: initialTab opens straight to a tab (e.g. Group); categoryId scopes the sheet so a single-list
    // header can open the Sort tab for that category. Store the id (not the Category) so the sheet reads
    // the live category at the mount site; a snapshot would show a stale sort after changing it.
    fun showSettingsDialog(initialTab: Int = 0, categoryId: Long? = null) {
        mutableState.update { it.copy(dialog = Dialog.SettingsSheet(initialTab, categoryId)) }
    }

    private var lastSelectionCategory: Long? = null

    fun clearSelection() {
        lastSelectionCategory = null
        mutableState.update { it.copy(selection = setOf()) }
    }

    // RK: manually merge the selected manga into one group (covers both library views)
    fun mergeSelection() {
        val ids = state.value.selection.toList()
        if (ids.size < 2) return
        screenModelScope.launchIO {
            // RK: each selected card's whole group is absorbed by the merge, so one call coalesces every source
            mergeManager.merge(ids)
            // RK: share any existing tracker across the newly merged group
            propagateTrackerLinks.fromSeed(ids.first())
        }
        clearSelection()
    }

    // RK: split the selected manga out of their merge groups (no-op for non-merged selections)
    fun unmergeSelection() {
        val ids = state.value.selection.toList()
        if (ids.isEmpty()) return
        screenModelScope.launchIO { mergeManager.unmerge(ids) }
        clearSelection()
    }

    fun toggleSelection(category: Category, manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { set ->
                if (!set.remove(manga.id)) set.add(manga.id)
            }
            lastSelectionCategory = category.id.takeIf { newSelection.isNotEmpty() }
            state.copy(selection = newSelection)
        }
    }

    /**
     * Selects all mangas between and including the given manga and the last pressed manga from the
     * same category as the given manga
     */
    fun toggleRangeSelection(category: Category, manga: LibraryManga) {
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val lastSelected = list.lastOrNull()
                if (lastSelectionCategory != category.id) {
                    list.add(manga.id)
                    return@mutate
                }

                val items = state.getItemsForCategoryId(category.id).fastMap { it.id }
                val lastMangaIndex = items.indexOf(lastSelected)
                val curMangaIndex = items.indexOf(manga.id)

                val selectionRange = when {
                    lastMangaIndex < curMangaIndex -> lastMangaIndex..curMangaIndex
                    curMangaIndex < lastMangaIndex -> curMangaIndex..lastMangaIndex
                    // We shouldn't reach this point
                    else -> return@mutate
                }
                selectionRange.mapNotNull { items[it] }.let(list::addAll)
            }
            lastSelectionCategory = category.id
            state.copy(selection = newSelection)
        }
    }

    fun selectAll() {
        lastSelectionCategory = null
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                state.getItemsForCategoryId(state.activeCategory?.id).map { it.id }.let(list::addAll)
            }
            state.copy(selection = newSelection)
        }
    }

    // RK: select every manga in a single category, or deselect them if all are already selected
    // (drives the single-list header's select-all circle).
    fun selectAllInCategory(category: Category) {
        lastSelectionCategory = null
        mutableState.update { state ->
            val ids = state.getItemsForCategory(category).map { it.id }
            val newSelection = state.selection.mutate { set ->
                if (ids.isNotEmpty() && ids.all { it in set }) set.removeAll(ids.toSet()) else set.addAll(ids)
            }
            state.copy(selection = newSelection)
        }
    }

    fun invertSelection() {
        lastSelectionCategory = null
        mutableState.update { state ->
            val newSelection = state.selection.mutate { list ->
                val itemIds = state.getItemsForCategoryId(state.activeCategory?.id).fastMap { it.id }
                val (toRemove, toAdd) = itemIds.partition { it in list }
                list.removeAll(toRemove)
                list.addAll(toAdd)
            }
            state.copy(selection = newSelection)
        }
    }

    fun search(query: String?) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun updateActiveCategoryIndex(index: Int) {
        val newIndex = mutableState.updateAndGet { state ->
            state.copy(activeCategoryIndex = index)
        }
            .coercedActiveCategoryIndex

        libraryPreferences.lastUsedCategory.set(newIndex)
    }

    fun openChangeCategoryDialog() {
        screenModelScope.launchIO {
            // Create a copy of selected manga
            val mangaList = state.value.selectedManga

            // Hide the default category because it has a different behavior than the ones from db.
            val categories = state.value.displayedCategories.filter { it.id != 0L }

            // RK: shared manga/novel category-diff over each entry's category ids (common = on all,
            // mix = on some) so the change-categories tri-state can't drift between the two types.
            val perManga = mangaList.map { getCategories.await(it.id).map { category -> category.id }.toSet() }
            val (common, mix) = categoryDiff(perManga)
            val preselected = categories
                .map {
                    when (it.id) {
                        in common -> CheckboxState.State.Checked(it)
                        in mix -> CheckboxState.TriState.Exclude(it)
                        else -> CheckboxState.State.None(it)
                    }
                }

            mutableState.update { it.copy(dialog = Dialog.ChangeCategory(mangaList, preselected)) }
        }
    }

    fun openDeleteMangaDialog() {
        val current = state.value
        // RK: N grouped sources to offer removing, when the selection includes a merged cover (else 0).
        val groupedCount = if (current.selectionContainsMerged) current.selectedMemberIds.size else 0
        mutableState.update { it.copy(dialog = Dialog.DeleteManga(current.selectedManga, groupedCount)) }
    }

    fun closeDialog() {
        mutableState.update { it.copy(dialog = null) }
    }

    sealed interface Dialog {
        // RK: initialTab = which settings tab to open on (0 = Filter)
        data class SettingsSheet(val initialTab: Int = 0, val categoryId: Long? = null) : Dialog
        data class ChangeCategory(
            val manga: List<Manga>,
            val initialSelection: List<CheckboxState<Category>>,
        ) : Dialog

        // RK: groupedSourceCount = N grouped sources behind the selection (0 = none merged, no extra option)
        data class DeleteManga(val manga: List<Manga>, val groupedSourceCount: Int = 0) : Dialog
    }

    @Immutable
    private data class ItemPreferences(
        val downloadBadge: Boolean,
        val unreadBadge: Boolean,
        val localBadge: Boolean,
        val languageBadge: Boolean,
        val skipOutsideReleasePeriod: Boolean,

        val globalFilterDownloaded: Boolean,
        val filterDownloaded: TriState,
        val filterUnread: TriState,
        val filterStarted: TriState,
        val filterBookmarked: TriState,
        val filterCompleted: TriState,
        val filterIntervalCustom: TriState,
        // RK --> net-new Reikai filter dims (lewd + include/exclude category) + badge data
        val filterLewd: TriState = TriState.DISABLED,
        val filterCategories: Boolean = false,
        val filterCategoriesInclude: Set<Long> = emptySet(),
        val filterCategoriesExclude: Set<Long> = emptySet(),
        val sourceBadge: Boolean = true,
        // RK <--
    )

    @Immutable
    data class LibraryData(
        val isInitialized: Boolean = false,
        val showSystemCategory: Boolean = false,
        val categories: List<Category> = emptyList(),
        val favorites: List<LibraryItem> = emptyList(),
        val tracksMap: Map</* Manga */ Long, List<Track>> = emptyMap(),
        val loggedInTrackerIds: Set<Long> = emptySet(),
        // RK: display-only custom title/cover overrides, keyed by real manga id. Never read by
        //     search/filter/sort/selection (those use the raw favorites); applied only at the
        //     per-category display read in State.getItemsForCategory.
        val customInfo: Map</* Manga */ Long, CustomMangaInfo> = emptyMap(),
    ) {
        val favoritesById by lazy { favorites.associateBy { it.id } }
    }

    @Immutable
    data class State(
        val isInitialized: Boolean = false,
        val isLoading: Boolean = true,
        val searchQuery: String? = null,
        val selection: Set</* Manga */ Long> = setOf(),
        val hasActiveFilters: Boolean = false,
        val showCategoryTabs: Boolean = false,
        val showMangaCount: Boolean = false,
        val showMangaContinueButton: Boolean = false,
        val dialog: Dialog? = null,
        val libraryData: LibraryData = LibraryData(),
        // RK -->
        val reikai: ReikaiLibraryState = ReikaiLibraryState(),
        // RK <--
        private val activeCategoryIndex: Int = 0,
        // RK --> ordered list, not a Map: Map.equals() ignores key order, so a category reorder
        // (category sort / move-dynamic-to-bottom) would compare equal and StateFlow would dedupe it,
        // leaving the UI unchanged. A List has order-sensitive equality, so reorders propagate.
        private val groupedFavorites: List<Pair<Category, List</* LibraryItem */ Long>>> = emptyList(),
        // RK <--
    ) {
        // RK --> derived from the ordered groupedFavorites list above (not a Map): keep an
        // id-keyed lookup so getItemsForCategory/getItemCountForCategory stay O(1) after the switch.
        val displayedCategories: List<Category> = groupedFavorites.map { it.first }

        private val groupedFavoritesById: Map<Long, List<Long>> by lazy {
            groupedFavorites.associate { it.first.id to it.second }
        }
        // RK <--

        val coercedActiveCategoryIndex = activeCategoryIndex.coerceIn(
            minimumValue = 0,
            maximumValue = displayedCategories.lastIndex.coerceAtLeast(0),
        )

        val activeCategory: Category? = displayedCategories.getOrNull(coercedActiveCategoryIndex)

        val isLibraryEmpty = libraryData.favorites.isEmpty()

        val selectionMode = selection.isNotEmpty()

        val selectedManga by lazy { selection.mapNotNull { libraryData.favoritesById[it]?.libraryManga?.manga } }

        // RK: any selected manga is part of a merge group (drives the bulk Unmerge action)
        val selectionContainsMerged: Boolean by lazy {
            selection.any { (libraryData.favoritesById[it]?.relatedMangaIds?.size ?: 0) > 1 }
        }

        // RK: ids of every grouped source-manga behind the current selection. A merged cover is a
        //     single selected id standing for its whole group (LibraryItem.relatedMangaIds); the
        //     merged-away members are collapsed out of favoritesById, so we keep their ids here and
        //     resolve the manga from the DB at delete time. Equals the selection when nothing merged.
        val selectedMemberIds: List<Long> by lazy {
            selection.flatMap { id ->
                val item = libraryData.favoritesById[id] ?: return@flatMap emptyList<Long>()
                item.relatedMangaIds.ifEmpty { listOf(id) }
            }.distinct()
        }

        fun getItemsForCategoryId(categoryId: Long?): List<LibraryItem> {
            if (categoryId == null) return emptyList()
            val category = displayedCategories.find { it.id == categoryId } ?: return emptyList()
            return getItemsForCategory(category)
        }

        fun getItemsForCategory(category: Category): List<LibraryItem> {
            // RK: look up by id (groupedFavorites is an ordered List, not a Map keyed by Category),
            //     then apply the display-only custom-info overlay. This is the sole render path, so
            //     the overrides never reach the raw favorites that search/filter/sort/selection read.
            return groupedFavoritesById[category.id].orEmpty().mapNotNull { id ->
                libraryData.favoritesById[id]?.let { item ->
                    val custom = libraryData.customInfo[item.libraryManga.manga.id] ?: return@let item
                    item.copy(
                        libraryManga = item.libraryManga.copy(
                            manga = item.libraryManga.manga.withCustomInfo(custom),
                        ),
                    )
                }
            }
        }

        fun getItemCountForCategory(category: Category): Int? {
            // RK: id-keyed lookup, see getItemsForCategory
            return if (showMangaCount || !searchQuery.isNullOrEmpty()) groupedFavoritesById[category.id]?.size else null
        }

        fun getToolbarTitle(
            defaultTitle: String,
            defaultCategoryTitle: String,
            page: Int,
        ): LibraryToolbarTitle {
            val category = displayedCategories.getOrNull(page) ?: return LibraryToolbarTitle(defaultTitle)
            val categoryName = when {
                category.isSystemCategory -> defaultCategoryTitle
                // RK: dynamic-grouping categories store an encoded name; show the decoded label.
                ReikaiDynamicCategory.isDynamic(category) -> ReikaiDynamicCategory.displayName(category)
                else -> category.name
            }
            // RK: "Always show current category" forces the category name into the title.
            val title = if (reikai.showCategoryInTitle || !showCategoryTabs) categoryName else defaultTitle
            val count = when {
                !showMangaCount -> null
                !showCategoryTabs -> getItemCountForCategory(category)
                // Whole library count
                else -> libraryData.favorites.size
            }
            return LibraryToolbarTitle(title, count)
        }
    }
}
