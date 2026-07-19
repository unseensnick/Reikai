package reikai.presentation.library.novels

import android.app.Application
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import reikai.data.novel.NovelStatusCode
import reikai.domain.category.CATEGORY_HIDDEN_MASK
import reikai.domain.category.categoryDiff
import reikai.domain.category.categoryFilterActive
import reikai.domain.category.matchesCategoryFilter
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.novel.NovelCategoryRepository
import reikai.domain.novel.NovelChapterAggregation
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.GetNovelCategories
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.NovelCategory
import reikai.domain.novel.model.NovelCategoryUpdate
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelLibrarySort
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.model.comparator
import reikai.domain.novel.model.toCategory
import reikai.domain.novel.model.withCustomInfo
import reikai.domain.novel.track.PropagateNovelTrackerLinks
import reikai.domain.novel.track.toUiTrack
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import reikai.presentation.category.toLongIdSet
import reikai.presentation.library.DynItem
import reikai.presentation.library.LibraryDynamicGrouping
import reikai.presentation.library.LibraryGroup
import reikai.presentation.library.ReikaiDynamicCategory
import reikai.presentation.library.reikaiSortCategories
import reikai.presentation.novel.selectChaptersForDownloadAction
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.util.Locale
import kotlin.random.Random

/**
 * Drives the novel half of the Library tab. It reads the favorited novels + novel categories
 * reactively, disguises each novel as the library's manga-shaped [LibraryItem] (negative id), filters
 * and per-category-sorts them, and exposes the same accessor surface
 * [eu.kanade.tachiyomi.ui.library.LibraryScreenModel.State] does so `LibraryTab` can feed the existing
 * views from either model based on the content-type chip. Mihon's library core is untouched.
 *
 * Selection is keyed by the negative synthetic id; [State.selectedNovelIds] maps back to real novel ids
 * for the multi-select actions (download / delete / change-category / mark-read). Display settings stay
 * shared with manga; tracker filter/sort/group reuse the shared tracker machinery via [getNovelTracks].
 */
class NovelLibraryScreenModel :
    StateScreenModel<NovelLibraryScreenModel.State>(State()) {

    private val context: Application by injectLazy()
    private val novelRepository: NovelRepository by injectLazy()
    private val updateNovel: UpdateNovel by injectLazy()
    private val setNovelReadStatus: SetNovelReadStatus by injectLazy()
    private val novelChapterRepository: NovelChapterRepository by injectLazy()
    private val novelCategoryRepository: NovelCategoryRepository by injectLazy()
    private val novelDownloadManager: NovelDownloadManager by injectLazy()
    private val novelDownloadCache: NovelDownloadCache by injectLazy()
    private val getNovelCategories: GetNovelCategories by injectLazy()

    // Per-entry custom title/cover overrides, overlaid on the displayed rows (display-only).
    private val getCustomNovelInfo: GetCustomNovelInfo by injectLazy()
    private val setNovelCategories: SetNovelCategories by injectLazy()
    private val libraryPreferences: LibraryPreferences by injectLazy()
    private val basePreferences: BasePreferences by injectLazy()
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences by injectLazy()
    private val sourceManager: NovelSourceManager by injectLazy()
    private val mergeManager: NovelMergeManager by injectLazy()
    private val mergeGroupRepository: MergeGroupRepository by injectLazy()
    private val propagateNovelTrackerLinks: PropagateNovelTrackerLinks by injectLazy()
    private val installer: LnPluginInstaller by injectLazy()
    private val trackerManager: TrackerManager by injectLazy()
    private val getNovelTracks: GetNovelTracks by injectLazy()

    /** Sticky Manga/Novels chip for the Library tab (owned here so it's read outside a Composable). */
    val contentType: StateFlow<ContentType> = reikaiLibraryPreferences.libraryContentType.changes()
        .stateIn(screenModelScope, SharingStarted.Eagerly, reikaiLibraryPreferences.libraryContentType.get())

    fun setContentType(type: ContentType) = reikaiLibraryPreferences.libraryContentType.set(type)

    private val searchQuery = MutableStateFlow<String?>(null)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())

    // Keyed by category name (header key), matching the manga collapse convention. Session-scoped.
    private val collapsedCategories = MutableStateFlow<Set<String>>(emptySet())

    // Anchor for range-select; not reactive (mirrors LibraryScreenModel.lastSelectionCategory).
    private var lastSelectionCategory: Long? = null

    private val mutableDialog = MutableStateFlow<Dialog?>(null)
    val dialog: StateFlow<Dialog?> = mutableDialog.asStateFlow()

    /** Reactive grouping inputs folded into the main combine so a collapse toggle re-sinks groups. */
    private data class GroupingInputs(val settings: LibrarySettings, val collapsed: Set<String>, val atBottom: Boolean)

    init {
        // Load the plugin host so the library can resolve each novel's source (lang + source-icon
        // badges); the source flow below re-emits buildState once the sources register.
        screenModelScope.launchIO { runCatching { installer.ensureLoaded() } }
        screenModelScope.launchIO {
            combine(
                getNovelCategories.subscribe(),
                // Re-emit when sources (un)register so `sourceManager.get(...)` resolves once loaded.
                // The custom-info overlay rides with the library so a title/cover edit re-emits too.
                combine(
                    novelRepository.getLibraryNovelAsFlow()
                        .combine(sourceManager.sources) { library, _ -> library }
                        // Re-emit when a download/delete changes the disk index so the badge + filter refresh.
                        .combine(novelDownloadCache.changes) { library, _ -> library },
                    getCustomNovelInfo.subscribeAll(),
                    // Whole-library novel tracks (novelId -> tracks) ride with the library so a bind/unbind
                    // re-sinks the tracker filter/sort/group; folded here to keep the main combine at 5 args.
                    getNovelTracks.subscribeAll(),
                    ::Triple,
                ),
                searchQuery,
                selection,
                // Collapse set + at-bottom pref ride with settings so a collapse toggle rebuilds the
                // grouping and re-sinks collapsed dynamic groups (the manga reactivity pattern).
                combine(
                    settingsFlow(),
                    collapsedCategories,
                    reikaiLibraryPreferences.collapsedDynamicAtBottom.changes(),
                ) { settings, collapsed, atBottom -> GroupingInputs(settings, collapsed, atBottom) },
            ) { categories, (library, customInfo, tracks), query, sel, grouping ->
                buildState(
                    categories, library, customInfo, tracks, query, sel,
                    grouping.settings, grouping.collapsed, grouping.atBottom,
                )
            }.collectLatest { built ->
                // Preserve the live searchQuery (and active page): the async buildState lags the user's
                // typing, so overwriting searchQuery here resets the search field to a stale value mid-
                // input and scrambles fast keystrokes. search() owns searchQuery synchronously instead.
                mutableState.update { current ->
                    built.copy(
                        activeCategoryIndex = current.activeCategoryIndex,
                        searchQuery = current.searchQuery,
                    )
                }
            }
        }
    }

    private fun badgePrefsFlow(): Flow<BadgePrefs> = combine(
        libraryPreferences.downloadBadge.changes(),
        libraryPreferences.unreadBadge.changes(),
        libraryPreferences.languageBadge.changes(),
        reikaiLibraryPreferences.sourceBadge.changes(),
    ) { download, unread, language, source -> BadgePrefs(download, unread, language, source) }

    /** Folds the badge, sort, and filter prefs into one flow so the main combine stays at its 5-arg max. */
    private fun settingsFlow(): Flow<LibrarySettings> {
        val miscFlow = combine(
            reikaiLibraryPreferences.novelLibraryDefaultSort.changes(),
            reikaiLibraryPreferences.novelLibraryRandomSeed.changes(),
            libraryPreferences.showContinueReadingButton.changes(),
            reikaiLibraryPreferences.showHiddenCategories.changes(),
            reikaiLibraryPreferences.categorySortOrder.changes(),
        ) { sort, seed, cont, showHidden, catSort -> Misc(sort, seed, cont, showHidden, catSort) }
        val triStateFilterFlow = combine(
            reikaiLibraryPreferences.novelLibraryFilterDownloaded.changes(),
            reikaiLibraryPreferences.novelLibraryFilterUnread.changes(),
            reikaiLibraryPreferences.novelLibraryFilterStarted.changes(),
            reikaiLibraryPreferences.novelLibraryFilterCompleted.changes(),
            reikaiLibraryPreferences.novelLibraryFilterBookmarked.changes(),
        ) { d, u, s, c, b -> NovelFilters(d, u, s, c, b) }
        // Category include/exclude rides in its own sub-flow so the tri-state combine stays at its 5-arg max.
        val categoryFilterFlow = combine(
            reikaiLibraryPreferences.novelLibraryFilterCategories.changes(),
            reikaiLibraryPreferences.novelLibraryFilterCategoriesInclude.changes(),
            reikaiLibraryPreferences.novelLibraryFilterCategoriesExclude.changes(),
        ) { enabled, include, exclude ->
            val inc = include.toLongIdSet()
            val exc = exclude.toLongIdSet()
            Triple(categoryFilterActive(enabled, inc, exc), inc, exc)
        }
        val filterFlow = combine(
            triStateFilterFlow,
            categoryFilterFlow,
            basePreferences.downloadedOnly.changes(),
            trackingFilterFlow(),
        ) { base, (active, inc, exc), downloadedOnly, trackingFilter ->
            FilterSettings(
                base.copy(categoriesActive = active, categoriesInclude = inc, categoriesExclude = exc),
                downloadedOnly,
                trackingFilter,
            )
        }
        val mergeFlow = combine(
            mergeGroupRepository.getAllMembershipsAsFlow(ContentType.NOVELS),
            reikaiLibraryPreferences.seriesMergingEnabled.changes(),
            reikaiLibraryPreferences.showNovelMergeSourceIcons.changes(),
        ) { membership, mergingEnabled, showIcons ->
            MergeSettings(membership, mergingEnabled, showIcons)
        }
        return combine(
            badgePrefsFlow(),
            miscFlow,
            filterFlow,
            mergeFlow,
            reikaiLibraryPreferences.groupNovelLibraryBy.changes(),
        ) { badges, misc, filterSettings, merge, groupBy ->
            LibrarySettings(
                badges, misc.defaultSort, misc.randomSeed, misc.showContinue, misc.showHidden,
                filterSettings.filters, filterSettings.downloadedOnly, merge, misc.categorySortOrder, groupBy,
                filterSettings.trackingFilter,
            )
        }
    }

    /**
     * Per-logged-in-tracker filter state (trackerId -> tri-state), mirroring the manga library's
     * [eu.kanade.tachiyomi.ui.library.LibraryScreenModel.getTrackingFiltersFlow]. The map's keys double
     * as the logged-in tracker id set (used to score/status-resolve only logged-in trackers below).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun trackingFilterFlow(): Flow<Map<Long, TriState>> =
        trackerManager.loggedInTrackersFlow().flatMapLatest { trackers ->
            if (trackers.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    trackers.map { tracker ->
                        reikaiLibraryPreferences.novelFilterTracking(tracker.id.toInt()).changes()
                            .map { tracker.id to it }
                    },
                ) { it.toMap() }
            }
        }

    private fun buildState(
        categories: List<NovelCategory>,
        library: List<LibraryNovel>,
        customInfo: List<CustomNovelInfo>,
        tracks: Map<Long, List<NovelTrack>>,
        query: String?,
        sel: Set<Long>,
        settings: LibrarySettings,
        collapsedKeys: Set<String>,
        atBottom: Boolean,
    ): State {
        // Downloaded state is disk-derived (NovelDownloadCache), not a DB column, so fill each novel's
        // download count from the cache before it feeds the filter, sort, collapse, and badge.
        val withCounts = library.map {
            it.copy(downloadCount = novelDownloadCache.getDownloadCount(it.novel).toLong())
        }
        val filtered = withCounts.filter { novel ->
            (query.isNullOrBlank() || novel.matchesQuery(query)) &&
                settings.filters.matches(novel, settings.downloadedOnly)
        }
        // Collapse merged groups into one representative entry (the most-chapters novel).
        val allGroups = NovelMergeCollapse.collapse(
            filtered,
            settings.merge.membership,
            settings.merge.mergingEnabled,
        )
        // Union each merge group's member tracks (deduped per tracker), keyed by the rep's real novel id,
        // so the tracker filter/sort/group reflect a track bound on ANY grouped source. Synchronous:
        // reads the in-memory group members, never the suspend awaitGroup.
        val loggedInTrackerIds = settings.trackingFilter.keys
        val tracksByRep: Map<Long, List<NovelTrack>> = allGroups.associate { group ->
            group.representative.novel.id to group.memberIds
                .flatMap { tracks[it].orEmpty() }
                .distinctBy { it.trackerId }
        }
        // Per-rep mean tracker score (0-10, logged-in trackers only; unscored reps omitted), for the sort.
        val trackerMeanScores: Map<Long, Double> = buildMap {
            tracksByRep.forEach { (repId, repTracks) ->
                val scores = repTracks
                    .filter { it.trackerId in loggedInTrackerIds }
                    .mapNotNull {
                        trackerManager.get(it.trackerId)?.get10PointScore(it.toUiTrack())?.takeIf { s ->
                            s >
                                0.0
                        }
                    }
                if (scores.isNotEmpty()) put(repId, scores.average())
            }
        }
        // Tracker-status filter (merge-aware, post-collapse): drop groups whose unioned trackers fail the
        // include/exclude tri-state, mirroring the manga library's filterFnTracking.
        val excludedTrackers = settings.trackingFilter.filterValues { it == TriState.ENABLED_NOT }.keys
        val includedTrackers = settings.trackingFilter.filterValues { it == TriState.ENABLED_IS }.keys
        val collapsed = if (excludedTrackers.isEmpty() && includedTrackers.isEmpty()) {
            allGroups
        } else {
            allGroups.filter { group ->
                val trackerIds = tracksByRep[group.representative.novel.id].orEmpty().map { it.trackerId }
                val isExcluded = excludedTrackers.isNotEmpty() && trackerIds.any { it in excludedTrackers }
                val isIncluded = includedTrackers.isEmpty() || trackerIds.any { it in includedTrackers }
                !isExcluded && isIncluded
            }
        }
        // Keyed by the representative's negative synthetic id (== the LibraryItem id), for the comparator.
        val novelById = collapsed.associate { -it.representative.novel.id to it.representative }
        // novelId -> source id, to resolve each grouped source's icon for the merge badge.
        val sourceByNovelId = library.associate { it.novel.id to it.novel.source }
        // Display-only custom-info overlay. Applied to the representative's display copy only (below),
        // keyed by the real novel id; collapse, grouping, sort and matchesQuery keep using the raw
        // novel via `novelById`, so the overrides never feed those operations.
        val overlay = customInfo.associateBy { it.novelId }
        val items = collapsed.map { group ->
            val rep = group.representative.let { r -> r.copy(novel = r.novel.withCustomInfo(overlay[r.novel.id])) }
            // lnreader plugins mostly declare lang as a full English name ("English"); the badge wants a
            // 2-char code like the manga side, so reduce it (codes pass through unchanged).
            val source = sourceManager.get(rep.novel.source)
            val lang = languageCodeOf(source?.lang.orEmpty())
            val item = rep.toLibraryItem(
                settings.badges.download,
                settings.badges.unread,
                settings.badges.language,
                lang,
                sourceBadge = settings.badges.source,
                sourceSite = source?.site,
                sourceIconUrl = source?.iconUrl,
            )
            if (group.memberIds.size > 1) {
                // Stamp the merge badge (group member ids, negative) + summed downloads onto the rep.
                // When the merge-icon setting is on, also resolve each grouped source's icon URL.
                val iconUrls = if (settings.merge.showSourceIcons) {
                    group.memberIds
                        .mapNotNull { id -> sourceByNovelId[id]?.let { sourceManager.get(it)?.iconUrl } }
                        .distinct()
                } else {
                    emptyList()
                }
                item.copy(
                    downloadCount = group.totalDownloadCount.toInt(),
                    relatedMangaIds = group.memberIds.map { -it },
                    badges = item.badges.copy(
                        downloadCount = if (settings.badges.download) group.totalDownloadCount.toInt() else 0,
                        mergedSourceIconUrls = iconUrls,
                    ),
                )
            } else {
                item
            }
        }
        val byId = items.associateBy { it.id }
        val flagsByCat = categories.associate { it.id to it.flags }
        val defaultSort = NovelLibrarySort.fromFlag(settings.defaultSort)

        val grouped: List<Pair<Category, List<Long>>> = if (settings.groupBy == LibraryGroup.BY_DEFAULT) {
            // Bucket item ids by category id; uncategorized (no real category) goes to Default (id 0).
            val byCategory = LinkedHashMap<Long, MutableList<Long>>()
            items.forEach { item ->
                val cats = item.libraryManga.categories.filter { it != NovelCategory.UNCATEGORIZED_ID }
                if (cats.isEmpty()) {
                    byCategory.getOrPut(NovelCategory.UNCATEGORIZED_ID) { mutableListOf() }.add(item.id)
                } else {
                    cats.forEach { c -> byCategory.getOrPut(c) { mutableListOf() }.add(item.id) }
                }
            }

            // Encode the resolved default sort into the synthesized Default category's flags so its header
            // label reflects the actual sort (it's stored in a global pref, not a DB row). NovelLibrarySort
            // mirrors LibrarySort's bit layout, so the shared header's `category.sort` decodes it correctly.
            val defaultCategory =
                Category(
                    NovelCategory.UNCATEGORIZED_ID,
                    context.stringResource(MR.strings.label_default),
                    0L,
                    settings.defaultSort,
                )
            val visibleCategories = if (settings.showHidden) {
                categories
            } else {
                categories.filterNot { (it.flags and CATEGORY_HIDDEN_MASK) == CATEGORY_HIDDEN_MASK }
            }
            // Manual DB order first, then the Reikai category-sort-order pref (Off/A->Z/Z->A), matching the
            // manga library so the shared Display setting reorders novel categories too (system pinned top).
            val allCategories = reikaiSortCategories(
                (listOf(defaultCategory) + visibleCategories.map { it.toCategory() }).sortedBy { it.order },
                settings.categorySortOrder,
            )
            allCategories.mapNotNull { category ->
                val ids = byCategory[category.id] ?: return@mapNotNull null
                val sort = sortFor(category.id, flagsByCat[category.id] ?: 0L, defaultSort)
                val comparator = sort.comparator(settings.randomSeed, trackerMeanScores)
                category to ids.sortedWith { a, b -> comparator.compare(novelById.getValue(a), novelById.getValue(b)) }
            }
        } else {
            // Dynamic grouping (by source / tag / author / language / status) replaces categories.
            buildNovelDynamicGrouping(
                items,
                novelById,
                settings,
                defaultSort,
                collapsedKeys,
                atBottom,
                tracksByRep,
                trackerMeanScores,
            )
        }

        // Item id (negative) -> (source, url) so LibraryTab can open the (representative) novel.
        val routes = collapsed.associate {
            -it.representative.novel.id to NovelRoute(it.representative.novel.source, it.representative.novel.url)
        }

        return State(
            isLoading = false,
            searchQuery = query,
            selection = sel,
            groupedFavorites = grouped,
            favoritesById = byId,
            novelRoutes = routes,
            categorySortFlags = flagsByCat,
            defaultSortFlag = settings.defaultSort,
            hasActiveFilters = settings.filters.hasActive ||
                settings.trackingFilter.values.any { it != TriState.DISABLED },
            showContinueButton = settings.showContinue,
            collapsedCategories = collapsedKeys,
        )
    }

    private fun sortFor(categoryId: Long, flags: Long, default: NovelLibrarySort): NovelLibrarySort =
        if (categoryId == NovelCategory.UNCATEGORIZED_ID) default else NovelLibrarySort.forCategory(flags, default)

    /**
     * Bucket the novel library into synthetic dynamic categories via the shared kernel, resolving
     * per-novel metadata (source / language / status / tracking status) into id-keyed maps. Operates on
     * the merge-collapsed representatives, keyed by the negative synthetic item id so the result lines up
     * with [State.favoritesById]. Tracking-status uses each rep's unioned merge-group tracks.
     */
    private fun buildNovelDynamicGrouping(
        items: List<LibraryItem>,
        novelById: Map<Long, LibraryNovel>,
        settings: LibrarySettings,
        defaultSort: NovelLibrarySort,
        collapsedKeys: Set<String>,
        atBottom: Boolean,
        tracksByRep: Map<Long, List<NovelTrack>>,
        trackerMeanScores: Map<Long, Double>,
    ): List<Pair<Category, List<Long>>> {
        val groupType = settings.groupBy
        val dynItems = items.mapNotNull { item ->
            val novel = novelById[item.id]?.novel ?: return@mapNotNull null
            DynItem(item.id, novel.genre, novel.author, novel.artist)
        }

        val sourceMeta = if (groupType == LibraryGroup.BY_SOURCE) {
            items.mapNotNull { item ->
                val novel = novelById[item.id]?.novel ?: return@mapNotNull null
                // The slug is the encoded disambiguator (sourceId() is never read); the name is the label.
                item.id to ((sourceManager.get(novel.source)?.name ?: novel.source) to novel.source)
            }.toMap()
        } else {
            emptyMap()
        }

        val languageCodes = if (groupType == LibraryGroup.BY_LANGUAGE) {
            items.mapNotNull { item ->
                val novel = novelById[item.id]?.novel ?: return@mapNotNull null
                val lang = languageCodeOf(sourceManager.get(novel.source)?.lang.orEmpty()).takeUnless { it.isBlank() }
                    ?: return@mapNotNull null
                item.id to lang
            }.toMap()
        } else {
            emptyMap()
        }

        val statusNames = if (groupType == LibraryGroup.BY_STATUS) {
            items.mapNotNull { item ->
                val novel = novelById[item.id]?.novel ?: return@mapNotNull null
                item.id to context.stringResource(NovelStatusCode.toStringRes(novel.status))
            }.toMap()
        } else {
            emptyMap()
        }

        // Group by the first logged-in tracker's status on any grouped source (mirrors the manga library).
        val loggedInTrackerIds = settings.trackingFilter.keys
        val trackStatuses = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
            items.mapNotNull { item ->
                val novel = novelById[item.id]?.novel ?: return@mapNotNull null
                val track = tracksByRep[novel.id].orEmpty()
                    .firstOrNull { it.trackerId in loggedInTrackerIds } ?: return@mapNotNull null
                val statusRes = trackerManager.get(track.trackerId)?.getStatus(track.status) ?: return@mapNotNull null
                item.id to context.stringResource(statusRes)
            }.toMap()
        } else {
            emptyMap()
        }

        val groups = LibraryDynamicGrouping.build(
            items = dynItems,
            groupType = groupType,
            inheritedSortFlag = settings.defaultSort,
            collapsedDynamicCategories = collapsedKeys,
            collapsedDynamicAtBottom = atBottom,
            unknownLabel = context.stringResource(MR.strings.unknown),
            notTrackedLabel = context.stringResource(MR.strings.not_tracked),
            ungroupedLabel = context.stringResource(MR.strings.group_ungrouped),
            categorySortOrder = settings.categorySortOrder,
            sourceMeta = sourceMeta,
            languageCodes = languageCodes,
            // Render the group-by-language header as the full name ("English"), not the bare code,
            // matching the manga library; the cover badge still uses the short code separately.
            languageDisplay = { code -> Locale.forLanguageTag(code).displayName.ifBlank { code } },
            statusNames = statusNames,
            trackStatuses = trackStatuses,
        )

        // Dynamic groups have no per-category sort, so they all use the library default sort.
        val comparator = defaultSort.comparator(settings.randomSeed, trackerMeanScores)
        return groups.map { (category, ids) ->
            category to ids.sortedWith { a, b -> comparator.compare(novelById.getValue(a), novelById.getValue(b)) }
        }
    }

    // --- search / selection / collapse mutators (read by LibraryTab) ---

    fun search(query: String?) {
        // Update the field's state synchronously so it stays responsive to fast typing (mirrors the
        // manga LibraryScreenModel); searchQuery also drives the async filter combine below.
        mutableState.update { it.copy(searchQuery = query) }
        searchQuery.value = query
    }

    fun clearSelection() {
        lastSelectionCategory = null
        selection.value = emptySet()
    }

    fun toggleSelection(categoryId: Long, itemId: Long) {
        selection.update { if (itemId in it) it - itemId else it + itemId }
        lastSelectionCategory = categoryId.takeIf { selection.value.isNotEmpty() }
    }

    /** Selects every item between the last-selected and [itemId] within the same category. */
    fun toggleRangeSelection(categoryId: Long, itemId: Long) {
        val items = state.value.itemIdsForCategory(categoryId)
        val last = selection.value.lastOrNull()
        if (lastSelectionCategory != categoryId || last == null || last !in items || itemId !in items) {
            selection.update { it + itemId }
        } else {
            val from = items.indexOf(last)
            val to = items.indexOf(itemId)
            val range = items.subList(minOf(from, to), maxOf(from, to) + 1)
            selection.update { it + range }
        }
        lastSelectionCategory = categoryId
    }

    fun selectAll() {
        lastSelectionCategory = null
        val ids = state.value.itemIdsForCategory(state.value.activeCategory?.id)
        selection.update { it + ids }
    }

    fun selectAllInCategory(categoryId: Long) {
        lastSelectionCategory = null
        val ids = state.value.itemIdsForCategory(categoryId)
        selection.update { sel -> if (ids.isNotEmpty() && ids.all { it in sel }) sel - ids.toSet() else sel + ids }
    }

    fun invertSelection() {
        lastSelectionCategory = null
        val ids = state.value.itemIdsForCategory(state.value.activeCategory?.id)
        selection.update { sel -> sel + ids.filterNot { it in sel } - ids.filter { it in sel }.toSet() }
    }

    fun toggleCategoryCollapse(headerKey: String) {
        collapsedCategories.update { if (headerKey in it) it - headerKey else it + headerKey }
    }

    /** Collapse all categories if any is expanded, else expand all (the hopper "toggle collapse").
     *  Dynamic groups key by their encoded header key (matching the collapse + sink), real ones by id. */
    fun toggleAllCategoriesCollapsed(categories: List<Category>) {
        val keys = categories.map {
            if (ReikaiDynamicCategory.isDynamic(it)) ReikaiDynamicCategory.headerKey(it) else it.id.toString()
        }.toSet()
        collapsedCategories.update { current -> if (current.containsAll(keys)) current - keys else current + keys }
    }

    fun updateActiveCategoryIndex(index: Int) {
        mutableState.update { it.copy(activeCategoryIndex = index) }
    }

    // --- multi-select actions ---

    /** Manually merge the selected novels into one group (covers both library views). */
    fun mergeSelection() {
        val ids = state.value.selectedNovelIds
        if (ids.size < 2) return
        screenModelScope.launchIO {
            // each selected card's whole group is absorbed by the merge, so one call coalesces every source
            mergeManager.mergeNovels(ids)
            clearSelection()
        }
    }

    /** Split the selected novels out of their merge groups (no-op for non-merged selections). */
    fun unmergeSelection() {
        val ids = state.value.selectedNovelIds
        if (ids.isEmpty()) return
        screenModelScope.launchIO {
            // copy each group's trackers onto its members before splitting, so each keeps them.
            ids.forEach { propagateNovelTrackerLinks.fromSeed(it) }
            mergeManager.unmergeNovels(ids)
            clearSelection()
        }
    }

    fun markReadSelection(read: Boolean) {
        val novelIds = state.value.selectedNovelIds
        screenModelScope.launchIO {
            // The interactor groups by novel for delete-after-read, so pass every selected novel's chapters.
            val chapters = novelIds.flatMap { novelChapterRepository.getByNovelId(it) }
            setNovelReadStatus.await(read, chapters)
            clearSelection()
        }
    }

    fun performDownloadAction(action: DownloadAction) {
        val novelIds = state.value.selectedNovelIds
        screenModelScope.launchIO {
            novelIds.forEach { id ->
                val novel = novelRepository.getById(id) ?: return@forEach
                val chapters = novelChapterRepository.getByNovelId(id)
                val downloadedIds = chapters
                    .filter { novelDownloadManager.isChapterDownloaded(novel, it) }
                    .mapTo(HashSet()) { it.id }
                val queuedIds = novelDownloadManager.queueState.value
                    .filter { it.novelId == id }
                    .mapTo(HashSet()) { it.chapterId }
                val targets = selectChaptersForDownloadAction(chapters, action, downloadedIds + queuedIds)
                if (targets.isNotEmpty()) novelDownloadManager.downloadChapters(targets)
            }
            clearSelection()
        }
    }

    fun openChangeCategoryDialog() {
        screenModelScope.launchIO {
            val novelIds = state.value.selectedNovelIds
            // All non-default categories, not just the ones currently shown (empty categories are
            // hidden from the library grid but must still be assignable here).
            val categories = getNovelCategories.await().filterNot { it.isSystemCategory }.map { it.toCategory() }
            val perNovel = novelIds.map { getNovelCategories.awaitByNovelId(it).map { c -> c.id }.toSet() }
            val (common, mix) = categoryDiff(perNovel)
            val preselected: List<CheckboxState<Category>> = categories.map { cat ->
                when (cat.id) {
                    in common -> CheckboxState.State.Checked(cat)
                    in mix -> CheckboxState.TriState.Exclude(cat)
                    else -> CheckboxState.State.None(cat)
                }
            }
            mutableDialog.value = Dialog.ChangeCategory(novelIds, preselected)
        }
    }

    fun setNovelCategories(novelIds: List<Long>, addCategories: List<Long>, removeCategories: List<Long>) {
        screenModelScope.launchIO {
            novelIds.forEach { novelId ->
                val current = getNovelCategories.awaitByNovelId(novelId).map { it.id }
                val new = (current - removeCategories.toSet() + addCategories).distinct()
                setNovelCategories.await(novelId, new)
            }
            clearSelection()
            dismissDialog()
        }
    }

    fun openDeleteDialog() {
        val current = state.value
        // N grouped sources to offer removing, when the selection includes a merged cover (else 0).
        val groupedCount = if (current.selectionContainsMerged) current.selectedNovelIdsExpanded.size else 0
        mutableDialog.value = Dialog.Delete(current.selectedNovelIds, groupedCount)
    }

    fun removeNovels(
        novelIds: List<Long>,
        deleteFromLibrary: Boolean,
        deleteDownloads: Boolean,
        // Expand merged covers to every grouped source, so the whole series leaves the library.
        removeGroupedSources: Boolean = false,
    ) {
        screenModelScope.launchIO {
            val targets = if (removeGroupedSources) state.value.selectedNovelIdsExpanded else novelIds
            targets.forEach { novelId ->
                if (deleteFromLibrary) {
                    updateNovel.awaitUpdateFavorite(novelId, favorite = false)
                }
                if (deleteDownloads) {
                    val novel = novelRepository.getById(novelId)
                    val downloaded = if (novel == null) {
                        emptyList()
                    } else {
                        novelChapterRepository.getByNovelId(novelId)
                            .filter { novelDownloadManager.isChapterDownloaded(novel, it) }
                    }
                    if (downloaded.isNotEmpty()) novelDownloadManager.deleteChapters(downloaded)
                }
            }
            clearSelection()
            dismissDialog()
        }
    }

    /** The next-unread chapter to resume. For a merged novel this pools the whole group (the unified
     *  cross-source list the details "All" view shows) to find the first unread; the reader itself
     *  resolves the group order for prev/next, so only the chapter is returned. */
    suspend fun getResume(repNovelId: Long): NovelChapter? {
        val rep = novelRepository.getById(repNovelId) ?: return null
        val memberIds = mergeManager.computeRelatedNovelIds(rep.id).toList()
        val ordered = if (memberIds.size <= 1) {
            novelChapterRepository.getByNovelId(repNovelId).sortedBy { it.sourceOrder }
        } else {
            val byNovel = memberIds.associateWith { novelChapterRepository.getByNovelId(it) }
            val sourceIdByNovel = memberIds.associateWith { id -> novelRepository.getById(id)?.source.orEmpty() }
            NovelChapterAggregation.aggregate(
                byNovel,
                sourceIdByNovel,
                reikaiLibraryPreferences.preferredNovelSources.get(),
                mergeManager.overrideRankingMemberIds(rep.id),
            )
                // chapterNumber is the cross-source reading order (sourceOrder isn't comparable across sources).
                .sortedBy { it.chapterNumber }
        }
        return ordered.firstOrNull { !it.read }
    }

    // --- settings dialog (sort / filter) ---

    fun openSettingsDialog(categoryId: Long, initialTab: Int = 0) {
        mutableDialog.value = Dialog.Settings(categoryId, initialTab)
    }

    fun dismissDialog() {
        mutableDialog.value = null
    }

    /** Sets the sort for a category (or the library default for the synthesized Default category). */
    fun setSort(categoryId: Long, type: NovelLibrarySort.Type, isAscending: Boolean) {
        if (type == NovelLibrarySort.Type.Random) {
            reikaiLibraryPreferences.novelLibraryRandomSeed.set(Random.nextLong())
        }
        val flag = NovelLibrarySort(type, isAscending).toFlag()
        // Mirror manga's SetSortModeForCategory: a real category with categorized-display on is an
        // OVERRIDE (writes CUSTOMIZED via toFlag); otherwise this is the GLOBAL sort. Clearing overrides
        // happens only when the toggle is turned off (ResetNovelCategoryFlags), never here, so sorting the
        // Default bucket / a global change no longer wipes per-category overrides.
        val perCategory = categoryId != NovelCategory.UNCATEGORIZED_ID &&
            libraryPreferences.categorizedDisplaySettings.get()
        if (perCategory) {
            screenModelScope.launchIO {
                // Preserve the category's other flag bits (e.g. hidden); only rewrite the sort bits.
                val current = state.value.flagsForCategory(categoryId)
                val newFlags = (current and NovelLibrarySort.FLAGS_MASK.inv()) or flag
                novelCategoryRepository.update(NovelCategoryUpdate(id = categoryId, flags = newFlags))
            }
        } else {
            reikaiLibraryPreferences.novelLibraryDefaultSort.set(flag)
        }
    }

    /** Clear this category's per-category sort override so it follows the global sort again. */
    fun resetSort(categoryId: Long) {
        screenModelScope.launchIO {
            val current = state.value.flagsForCategory(categoryId)
            novelCategoryRepository.update(
                NovelCategoryUpdate(id = categoryId, flags = current and NovelLibrarySort.FLAGS_MASK.inv()),
            )
        }
    }

    // Dynamic grouping mode exposed for the settings dialog's Group tab.
    val groupLibraryBy: Preference<Int> get() = reikaiLibraryPreferences.groupNovelLibraryBy
    fun setGrouping(value: Int) = reikaiLibraryPreferences.groupNovelLibraryBy.set(value)

    // Global "Downloaded only" mode (More menu), exposed so the filter sheet can lock the Downloaded chip.
    val downloadedOnly: Preference<Boolean> get() = basePreferences.downloadedOnly

    // Filter prefs exposed for the settings dialog (read via collectAsState, cycled via toggleFilter).
    val filterDownloaded: Preference<TriState> get() = reikaiLibraryPreferences.novelLibraryFilterDownloaded
    val filterUnread: Preference<TriState> get() = reikaiLibraryPreferences.novelLibraryFilterUnread
    val filterStarted: Preference<TriState> get() = reikaiLibraryPreferences.novelLibraryFilterStarted
    val filterCompleted: Preference<TriState> get() = reikaiLibraryPreferences.novelLibraryFilterCompleted
    val filterBookmarked: Preference<TriState> get() = reikaiLibraryPreferences.novelLibraryFilterBookmarked

    /** Logged-in trackers, for the settings sheet's per-tracker filter rows + the tracker-score sort gate. */
    val trackersFlow: StateFlow<List<Tracker>> = trackerManager.loggedInTrackersFlow()
        .stateIn(screenModelScope, SharingStarted.WhileSubscribed(), trackerManager.loggedInTrackers())

    /** Per-tracker novel filter pref (read via collectAsState in the sheet), cycled via [toggleNovelTracker]. */
    fun novelFilterTracking(id: Int): Preference<TriState> = reikaiLibraryPreferences.novelFilterTracking(id)

    fun toggleNovelTracker(id: Int) = toggleFilter(reikaiLibraryPreferences.novelFilterTracking(id))

    // Include/exclude category filter (novel-specific keys); the shared CategoryFilterRow reads these.
    val filterCategoriesEnabled: Preference<Boolean> get() = reikaiLibraryPreferences.novelLibraryFilterCategories
    val filterCategoriesInclude: Preference<Set<String>>
        get() = reikaiLibraryPreferences.novelLibraryFilterCategoriesInclude
    val filterCategoriesExclude: Preference<Set<String>>
        get() = reikaiLibraryPreferences.novelLibraryFilterCategoriesExclude

    fun setFilterCategories(enabled: Boolean) {
        reikaiLibraryPreferences.novelLibraryFilterCategories.set(enabled)
    }

    fun setCategoryFilterSelections(include: Set<Long>, exclude: Set<Long>) {
        reikaiLibraryPreferences.novelLibraryFilterCategoriesInclude.set(include.map { it.toString() }.toSet())
        reikaiLibraryPreferences.novelLibraryFilterCategoriesExclude.set(exclude.map { it.toString() }.toSet())
    }

    /** Full novel category list (synthesized Default + user categories, sorted) for the filter picker.
     *  Not [State.displayedCategories]: that drops empty categories and is replaced by dynamic groups
     *  when grouping is on, neither of which suits a category filter. */
    val filterPickerCategories: StateFlow<List<Category>> = combine(
        getNovelCategories.subscribe(),
        reikaiLibraryPreferences.categorySortOrder.changes(),
    ) { categories, sortOrder ->
        val default = Category(NovelCategory.UNCATEGORIZED_ID, context.stringResource(MR.strings.label_default), 0L, 0L)
        reikaiSortCategories((listOf(default) + categories.map { it.toCategory() }).sortedBy { it.order }, sortOrder)
    }.stateIn(screenModelScope, SharingStarted.WhileSubscribed(), emptyList())

    fun toggleFilter(pref: Preference<TriState>) {
        pref.set(
            when (pref.get()) {
                TriState.DISABLED -> TriState.ENABLED_IS
                TriState.ENABLED_IS -> TriState.ENABLED_NOT
                TriState.ENABLED_NOT -> TriState.DISABLED
            },
        )
    }

    private fun TriState.matches(value: Boolean): Boolean = when (this) {
        TriState.DISABLED -> true
        TriState.ENABLED_IS -> value
        TriState.ENABLED_NOT -> !value
    }

    private data class BadgePrefs(
        val download: Boolean,
        val unread: Boolean,
        val language: Boolean,
        val source: Boolean,
    )

    private data class NovelFilters(
        val downloaded: TriState,
        val unread: TriState,
        val started: TriState,
        val completed: TriState,
        val bookmarked: TriState,
        val categoriesActive: Boolean = false,
        val categoriesInclude: Set<Long> = emptySet(),
        val categoriesExclude: Set<Long> = emptySet(),
    )

    private data class Misc(
        val defaultSort: Long,
        val randomSeed: Long,
        val showContinue: Boolean,
        val showHidden: Boolean,
        val categorySortOrder: Int,
    )

    private data class MergeSettings(
        val membership: Map<Long, Long>,
        val mergingEnabled: Boolean,
        val showSourceIcons: Boolean,
    )

    /** Carries the per-session filters plus the global Downloaded-only mode out of the filter sub-flow. */
    private data class FilterSettings(
        val filters: NovelFilters,
        val downloadedOnly: Boolean,
        val trackingFilter: Map<Long, TriState>,
    )

    private data class LibrarySettings(
        val badges: BadgePrefs,
        val defaultSort: Long,
        val randomSeed: Long,
        val showContinue: Boolean,
        val showHidden: Boolean,
        val filters: NovelFilters,
        val downloadedOnly: Boolean,
        val merge: MergeSettings,
        val categorySortOrder: Int,
        val groupBy: Int,
        // Per-logged-in-tracker filter (trackerId -> tri-state); keys are the logged-in tracker ids.
        val trackingFilter: Map<Long, TriState>,
    )

    private fun NovelFilters.matches(n: LibraryNovel, forceDownloaded: Boolean): Boolean =
        (if (forceDownloaded) TriState.ENABLED_IS else downloaded).matches(n.downloadCount > 0) &&
            unread.matches(n.unreadCount > 0) &&
            started.matches(n.hasStarted) &&
            completed.matches(n.novel.status == NovelStatusCode.COMPLETED.toLong()) &&
            bookmarked.matches(n.bookmarkCount > 0) &&
            (!categoriesActive || matchesCategoryFilter(n.categories, categoriesInclude, categoriesExclude))

    private val NovelFilters.hasActive: Boolean
        get() = categoriesActive ||
            listOf(downloaded, unread, started, completed, bookmarked).any { it != TriState.DISABLED }

    sealed interface Dialog {
        data class ChangeCategory(val novelIds: List<Long>, val preselected: List<CheckboxState<Category>>) : Dialog

        // groupedSourceCount = N grouped sources behind the selection (0 = none merged, no extra option)
        data class Delete(val novelIds: List<Long>, val groupedSourceCount: Int = 0) : Dialog
        data class Settings(val categoryId: Long, val initialTab: Int) : Dialog
    }

    data class State(
        val isLoading: Boolean = true,
        val searchQuery: String? = null,
        val selection: Set<Long> = emptySet(),
        val collapsedCategories: Set<String> = emptySet(),
        val activeCategoryIndex: Int = 0,
        val hasActiveFilters: Boolean = false,
        val showContinueButton: Boolean = false,
        private val groupedFavorites: List<Pair<Category, List<Long>>> = emptyList(),
        private val favoritesById: Map<Long, LibraryItem> = emptyMap(),
        private val novelRoutes: Map<Long, NovelRoute> = emptyMap(),
        private val categorySortFlags: Map<Long, Long> = emptyMap(),
        private val defaultSortFlag: Long = NovelLibrarySort.default.toFlag(),
    ) {
        val displayedCategories: List<Category> = groupedFavorites.map { it.first }

        private val groupedById: Map<Long, List<Long>> by lazy {
            groupedFavorites.associate { it.first.id to it.second }
        }

        val coercedActiveCategoryIndex = activeCategoryIndex.coerceIn(
            0,
            displayedCategories.lastIndex.coerceAtLeast(0),
        )

        val activeCategory: Category? = displayedCategories.getOrNull(coercedActiveCategoryIndex)

        val isLibraryEmpty = favoritesById.isEmpty()

        val selectionMode = selection.isNotEmpty()

        val selectedNovelIds: List<Long> by lazy { selection.map { -it } }

        /** Any selected entry is a merge group (drives the bulk Unmerge action). */
        val selectionContainsMerged: Boolean by lazy {
            selection.any { (favoritesById[it]?.relatedMangaIds?.size ?: 0) > 1 }
        }

        /** Every grouped source-novel behind the selection, as real novel ids. A merged cover is one
         *  selected synthetic id standing for its whole group (relatedMangaIds, in synthetic ids);
         *  this expands each to all members. Equals selectedNovelIds when nothing is merged. */
        val selectedNovelIdsExpanded: List<Long> by lazy {
            selection.flatMap { id ->
                val item = favoritesById[id] ?: return@flatMap emptyList<Long>()
                item.relatedMangaIds.ifEmpty { listOf(id) }
            }.distinct().map { -it }
        }

        fun getItemsForCategory(category: Category): List<LibraryItem> =
            groupedById[category.id].orEmpty().mapNotNull { favoritesById[it] }

        fun getItemCountForCategory(category: Category): Int? = groupedById[category.id]?.size

        /** Ordered item ids (negative) for a category, for range/select-all; null id = active category. */
        fun itemIdsForCategory(categoryId: Long?): List<Long> =
            categoryId?.let { groupedById[it] }.orEmpty()

        /** Raw `NovelCategory.flags` for a category (so a sort write can preserve the hidden bit). */
        fun flagsForCategory(categoryId: Long): Long = categorySortFlags[categoryId] ?: 0L

        /** Current sort for a category, for the settings dialog's Sort tab. */
        fun sortFor(categoryId: Long): NovelLibrarySort {
            val default = NovelLibrarySort.fromFlag(defaultSortFlag)
            return if (categoryId == NovelCategory.UNCATEGORIZED_ID) {
                default
            } else {
                NovelLibrarySort.forCategory(categorySortFlags[categoryId] ?: 0L, default)
            }
        }

        /** (source, url) for the disguised item id (negative), to open the novel details screen. */
        fun routeFor(itemId: Long): NovelRoute? = novelRoutes[itemId]

        /** A random favorited novel's route (the hopper "random, global" action). */
        fun randomRoute(): NovelRoute? = novelRoutes.values.randomOrNull()

        /** A random novel route within [categoryId] (the hopper "random, in category" action). */
        fun randomRouteInCategory(categoryId: Long?): NovelRoute? =
            itemIdsForCategory(categoryId).randomOrNull()?.let { routeFor(it) }
    }

    data class NovelRoute(val source: String, val url: String)
}

private fun LibraryNovel.matchesQuery(query: String): Boolean {
    val n = novel
    return n.title.contains(query, true) ||
        (n.author?.contains(query, true) ?: false) ||
        (n.artist?.contains(query, true) ?: false) ||
        (n.genre?.any { it.contains(query, true) } ?: false)
}

/**
 * Reduce an lnreader language value to a 2-char ISO 639-1 code for the library badge. Plugins mostly
 * declare a full English name ("English", "Turkish"); reverse-map it. Values already short (a code) pass
 * through; an unmatched name falls back to its first two chars.
 *
 * Memoized: the name to code mapping is static, but this runs for every novel on every library rebuild
 * (including each selection tap), and the reverse-map scans ~180 ISO languages per uncached name.
 */
private val languageCodeCache = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun languageCodeOf(value: String): String {
    if (value.isBlank() || value.length <= 3) return value
    return languageCodeCache.getOrPut(value) {
        Locale.getISOLanguages().firstOrNull {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).equals(value, ignoreCase = true)
        } ?: value.take(2)
    }
}
