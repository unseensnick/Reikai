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
import eu.kanade.presentation.components.SEARCH_DEBOUNCE_MILLIS
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
// RK -->
import reikai.domain.category.isHidden
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.domain.source.model.Source as DomainSource
import tachiyomi.domain.source.model.StubSource
import reikai.presentation.library.LibraryDynamicGrouping
import reikai.presentation.library.LibraryGroup
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.PropagateTrackerLinks
import reikai.presentation.library.MangaMergeCollapse
import reikai.presentation.library.ReikaiDynamicCategory
import reikai.presentation.library.ReikaiLibraryState
import reikai.presentation.library.reikaiSortCategories
import reikai.util.isLewd
// RK <--
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.compareToWithCollator
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
import tachiyomi.domain.library.model.sort
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.model.applyFilter
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlin.random.Random

class LibraryScreenModel(
    private val getLibraryManga: GetLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
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
    private val propagateTrackerLinks: PropagateTrackerLinks = Injekt.get(),
    // RK <--
) : StateScreenModel<LibraryScreenModel.State>(State()) {

    init {
        mutableState.update { state ->
            state.copy(activeCategoryIndex = libraryPreferences.lastUsedCategory.get())
        }
        screenModelScope.launchIO {
            combine(
                state.map { it.searchQuery }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MILLIS),
                getCategories.subscribe(),
                getFavoritesFlow(),
                combine(getTracksPerManga.subscribe(), getTrackingFiltersFlow(), ::Pair),
                getLibraryItemPreferencesFlow(),
            ) { searchQuery, categories, favorites, (tracksMap, trackingFilters), itemPreferences ->
                val showSystemCategory = favorites.any { it.libraryManga.categories.contains(0) }
                val filteredFavorites = favorites
                    .applyFilters(tracksMap, trackingFilters, itemPreferences)
                    .let { if (searchQuery == null) it else it.filter { m -> m.matches(searchQuery, sourceManager) } }

                LibraryData(
                    isInitialized = true,
                    showSystemCategory = showSystemCategory,
                    categories = categories,
                    favorites = filteredFavorites,
                    tracksMap = tracksMap,
                    loggedInTrackerIds = trackingFilters.keys,
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
            state
                .dropWhile { !it.libraryData.isInitialized }
                // RK --> branch on the Reikai grouping mode; dynamic grouping (Y3) and category
                // order (R3) replace Mihon's plain category bucketing. The distinct key includes
                // only the grouping-relevant Reikai fields so badge/hopper changes don't re-group.
                .map {
                    Triple(
                        it.libraryData,
                        it.reikai.groupingInputs(),
                        // drop categories an active filter/search emptied, unless the user keeps them
                        (it.hasActiveFilters || it.searchQuery != null) &&
                            !it.reikai.showEmptyCategoriesWhileFiltering,
                    )
                }
                .distinctUntilChanged()
                .map { (data, grouping, dropEmptyWhileFiltering) ->
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
                (
                    prefs.filterCategories &&
                        (prefs.filterCategoriesInclude.isNotEmpty() || prefs.filterCategoriesExclude.isNotEmpty())
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

    /** R3: order the category buckets (0 = manual/DB order, 1 = A->Z, 2 = Z->A; system pinned on top). */
    private fun Map<Category, List<Long>>.reorderReikaiCategories(categorySortOrder: Int): Map<Category, List<Long>> {
        if (categorySortOrder == 0 || isEmpty()) return this
        return reikaiSortCategories(keys.toList(), categorySortOrder).associateWith { getValue(it) }
    }

    /** Y3: bucket the library into synthetic dynamic categories, resolving per-manga metadata. */
    private fun buildReikaiDynamicGrouping(data: LibraryData, grouping: GroupingInputs): Map<Category, List<Long>> {
        val context = Injekt.get<Application>()
        val groupType = grouping.groupLibraryBy
        val library = data.favorites.map { it.libraryManga }

        val sourceMeta = if (groupType == LibraryGroup.BY_SOURCE) {
            library.associate { lm ->
                val source = sourceManager.getOrStub(lm.manga.source)
                lm.manga.id to (source.name to source.id)
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
            library.mapNotNull { lm ->
                val track = data.tracksMap[lm.manga.id].orEmpty()
                    .firstOrNull { it.trackerId in data.loggedInTrackerIds }
                    ?: return@mapNotNull null
                val statusRes = trackerManager.get(track.trackerId)?.getStatus(track.status)
                    ?: return@mapNotNull null
                lm.manga.id to context.stringResource(statusRes)
            }.toMap()
        } else {
            emptyMap()
        }

        return LibraryDynamicGrouping.build(
            library = library,
            groupType = groupType,
            inheritedSort = libraryPreferences.sortingMode.get(),
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
            applyFilter(filterUnread) { it.libraryManga.unreadCount > 0 }
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

            val mangaTracks = trackMap[item.id].orEmpty().map { it.trackerId }

            val isExcluded = excludedTracks.isNotEmpty() && mangaTracks.fastAny { it in excludedTracks }
            val isIncluded = includedTracks.isEmpty() || mangaTracks.fastAny { it in includedTracks }

            !isExcluded && isIncluded
        }

        // RK --> net-new Reikai filter dims (lewd + include/exclude category)
        val filterLewd = preferences.filterLewd
        val filterCategoriesActive = preferences.filterCategories &&
            (preferences.filterCategoriesInclude.isNotEmpty() || preferences.filterCategoriesExclude.isNotEmpty())
        val includeCategories = preferences.filterCategoriesInclude
        val excludeCategories = preferences.filterCategoriesExclude

        val filterFnLewd: (LibraryItem) -> Boolean = {
            applyFilter(filterLewd) {
                it.libraryManga.manga.isLewd(sourceManager.getOrStub(it.libraryManga.manga.source).name)
            }
        }

        val filterFnCategories: (LibraryItem) -> Boolean = catFilter@{ item ->
            if (!filterCategoriesActive) return@catFilter true
            val mangaCategories = item.libraryManga.categories
            val isIncluded = includeCategories.isEmpty() || mangaCategories.fastAny { it in includeCategories }
            val isExcluded = excludeCategories.isNotEmpty() && mangaCategories.fastAny { it in excludeCategories }
            isIncluded && !isExcluded
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
        showHiddenCategories: Boolean,
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

    private fun Map<Category, List</* LibraryItem */ Long>>.applySort(
        favoritesById: Map<Long, LibraryItem>,
        trackMap: Map<Long, List<Track>>,
        loggedInTrackerIds: Set<Long>,
    ): Map<Category, List</* LibraryItem */ Long>> {
        val sortAlphabetically: (LibraryItem, LibraryItem) -> Int = { manga1, manga2 ->
            val title1 = manga1.libraryManga.manga.title.lowercase()
            val title2 = manga2.libraryManga.manga.title.lowercase()
            title1.compareToWithCollator(title2)
        }

        val defaultTrackerScoreSortValue = -1.0
        val trackerScores by lazy {
            val trackerMap = trackerManager.getAll(loggedInTrackerIds).associateBy { e -> e.id }
            trackMap.mapValues { entry ->
                when {
                    entry.value.isEmpty() -> null
                    else ->
                        entry.value
                            .mapNotNull { trackerMap[it.trackerId]?.get10PointScore(it) }
                            .average()
                }
            }
        }

        fun LibrarySort.comparator(): Comparator<LibraryItem> = Comparator { manga1, manga2 ->
            when (this.type) {
                LibrarySort.Type.Alphabetical -> {
                    sortAlphabetically(manga1, manga2)
                }
                LibrarySort.Type.LastRead -> {
                    manga1.libraryManga.lastRead.compareTo(manga2.libraryManga.lastRead)
                }
                LibrarySort.Type.LastUpdate -> {
                    manga1.libraryManga.manga.lastUpdate.compareTo(manga2.libraryManga.manga.lastUpdate)
                }
                LibrarySort.Type.UnreadCount -> when {
                    // Ensure unread content comes first
                    manga1.libraryManga.unreadCount == manga2.libraryManga.unreadCount -> 0
                    manga1.libraryManga.unreadCount == 0L -> if (this.isAscending) 1 else -1
                    manga2.libraryManga.unreadCount == 0L -> if (this.isAscending) -1 else 1
                    else -> manga1.libraryManga.unreadCount.compareTo(manga2.libraryManga.unreadCount)
                }
                LibrarySort.Type.TotalChapters -> {
                    manga1.libraryManga.totalChapters.compareTo(manga2.libraryManga.totalChapters)
                }
                LibrarySort.Type.LatestChapter -> {
                    manga1.libraryManga.latestUpload.compareTo(manga2.libraryManga.latestUpload)
                }
                LibrarySort.Type.ChapterFetchDate -> {
                    manga1.libraryManga.chapterFetchedAt.compareTo(manga2.libraryManga.chapterFetchedAt)
                }
                LibrarySort.Type.DateAdded -> {
                    manga1.libraryManga.manga.dateAdded.compareTo(manga2.libraryManga.manga.dateAdded)
                }
                LibrarySort.Type.TrackerMean -> {
                    val item1Score = trackerScores[manga1.id] ?: defaultTrackerScoreSortValue
                    val item2Score = trackerScores[manga2.id] ?: defaultTrackerScoreSortValue
                    item1Score.compareTo(item2Score)
                }
                LibrarySort.Type.Random -> {
                    error("Why Are We Still Here? Just To Suffer?")
                }
            }
        }

        return mapValues { (key, value) ->
            if (key.sort.type == LibrarySort.Type.Random) {
                return@mapValues value.shuffled(Random(libraryPreferences.randomSortSeed.get()))
            }

            val manga = value.mapNotNull { favoritesById[it] }

            val comparator = key.sort.comparator()
                .let { if (key.sort.isAscending) it else it.reversed() }
                .thenComparator(sortAlphabetically)

            manga.sortedWith(comparator).map { it.id }
        }
    }

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
                filterCategoriesInclude = (it[14] as Set<*>).mapNotNull { id -> (id as? String)?.toLongOrNull() }.toSet(),
                filterCategoriesExclude = (it[15] as Set<*>).mapNotNull { id -> (id as? String)?.toLongOrNull() }.toSet(),
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
            val items = libraryManga.map { manga ->
                LibraryItem(
                    libraryManga = manga,
                    downloadCount = downloadManager.getDownloadCount(manga.manga),
                    unreadCount = manga.unreadCount,
                    isLocal = manga.manga.isLocal(),
                    badges = LibraryItem.Badges(
                        downloadCount = if (preferences.downloadBadge) {
                            downloadManager.getDownloadCount(manga.manga)
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
            // RK: collapse pref-based merge groups into one entry per group
            MangaMergeCollapse.collapse(
                items = items,
                manualMerges = mergePrefs.merges,
                manualUnmerges = mergePrefs.unmerges,
                autoMergeSameTitle = mergePrefs.autoMergeSameTitle,
                showMergeSourceIcons = mergePrefs.showMergeSourceIcons,
                resolveSource = ::resolveMergeSource,
            )
        }
    }

    // RK -->
    private data class MergePrefs(
        val merges: Set<String>,
        val unmerges: Set<String>,
        val autoMergeSameTitle: Boolean,
        val showMergeSourceIcons: Boolean,
    )

    private fun mergePrefsFlow(): Flow<MergePrefs> = combine(
        reikaiLibraryPreferences.mangaManualMerges.changes(),
        reikaiLibraryPreferences.mangaManualUnmerges.changes(),
        reikaiLibraryPreferences.autoMergeSameTitle.changes(),
        reikaiLibraryPreferences.showMergeSourceIcons.changes(),
    ) { merges, unmerges, auto, showIcons -> MergePrefs(merges, unmerges, auto, showIcons) }

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

    /**
     * Returns the common categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    private suspend fun getCommonCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        return mangas
            .map { getCategories.await(it.id).toSet() }
            .reduce { set1, set2 -> set1.intersect(set2) }
    }

    suspend fun getNextUnreadChapter(manga: Manga): Chapter? {
        return getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true).getNextUnread(manga, downloadManager)
    }

    /**
     * Returns the mix (non-common) categories for the given list of manga.
     *
     * @param mangas the list of manga.
     */
    private suspend fun getMixCategories(mangas: List<Manga>): Collection<Category> {
        if (mangas.isEmpty()) return emptyList()
        val mangaCategories = mangas.map { getCategories.await(it.id).toSet() }
        val common = mangaCategories.reduce { set1, set2 -> set1.intersect(set2) }
        return mangaCategories.flatten().distinct().subtract(common)
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
        val selection = state.value.selectedManga
        screenModelScope.launchNonCancellable {
            selection.forEach { manga ->
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
    fun removeMangas(mangas: List<Manga>, deleteFromLibrary: Boolean, deleteChapters: Boolean) {
        screenModelScope.launchNonCancellable {
            if (deleteFromLibrary) {
                val toDelete = mangas.map {
                    it.removeCovers(coverCache)
                    MangaUpdate(
                        favorite = false,
                        id = it.id,
                    )
                }
                updateManga.awaitAll(toDelete)
            }

            if (deleteChapters) {
                mangas.forEach { manga ->
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
        screenModelScope.launchNonCancellable {
            mangaList.forEach { manga ->
                val categoryIds = getCategories.await(manga.id)
                    .map { it.id }
                    .subtract(removeCategories.toSet())
                    .plus(addCategories)
                    .toList()

                setMangaCategories.await(manga.id, categoryIds)
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
        mergeManager.mergeManga(ids)
        // RK: share any existing tracker across the newly merged group
        screenModelScope.launchIO { propagateTrackerLinks.fromSeed(ids.first()) }
        clearSelection()
    }

    // RK: split the selected manga out of their merge groups (no-op for non-merged selections)
    fun unmergeSelection() {
        val ids = state.value.selection.toList()
        if (ids.isEmpty()) return
        screenModelScope.launchIO { mergeManager.unmergeManga(ids) }
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

            // Get indexes of the common categories to preselect.
            val common = getCommonCategories(mangaList)
            // Get indexes of the mix categories to preselect.
            val mix = getMixCategories(mangaList)
            val preselected = categories
                .map {
                    when (it) {
                        in common -> CheckboxState.State.Checked(it)
                        in mix -> CheckboxState.TriState.Exclude(it)
                        else -> CheckboxState.State.None(it)
                    }
                }

            mutableState.update { it.copy(dialog = Dialog.ChangeCategory(mangaList, preselected)) }
        }
    }

    fun openDeleteMangaDialog() {
        mutableState.update { it.copy(dialog = Dialog.DeleteManga(state.value.selectedManga)) }
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
        data class DeleteManga(val manga: List<Manga>) : Dialog
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
        // (R3 sort / move-dynamic-to-bottom) would compare equal and StateFlow would dedupe it,
        // leaving the UI unchanged. A List has order-sensitive equality, so reorders propagate.
        private val groupedFavorites: List<Pair<Category, List</* LibraryItem */ Long>>> = emptyList(),
        // RK <--
    ) {
        val displayedCategories: List<Category> = groupedFavorites.map { it.first }

        private val groupedFavoritesById: Map<Long, List<Long>> by lazy {
            groupedFavorites.associate { it.first.id to it.second }
        }

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

        fun getItemsForCategoryId(categoryId: Long?): List<LibraryItem> {
            if (categoryId == null) return emptyList()
            val category = displayedCategories.find { it.id == categoryId } ?: return emptyList()
            return getItemsForCategory(category)
        }

        fun getItemsForCategory(category: Category): List<LibraryItem> {
            return groupedFavoritesById[category.id].orEmpty().mapNotNull { libraryData.favoritesById[it] }
        }

        fun getItemCountForCategory(category: Category): Int? {
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
