package reikai.presentation.library.novels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.manga.DownloadAction
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.ui.library.LibraryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mihon.domain.library.model.search.QueryNode
import reikai.domain.category.GetNovelCategories
import reikai.domain.chapter.DownloadCandidates
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.AdultContentChecker
import reikai.domain.merge.DownloadUnitRow
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.merge.downloadedUnitsByGroup
import reikai.domain.merge.stitchInputChanges
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.downloadedChapterIds
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.NovelGroupChapters
import reikai.domain.novel.interactor.RemoveNovelsFromLibrary
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.ownersOf
import reikai.domain.novel.track.toUiTrack
import reikai.novel.download.NovelDownloadCache
import reikai.novel.download.NovelDownloadManager
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.NovelSourceManager
import reikai.presentation.library.LibraryBadgePrefs
import reikai.presentation.library.LibraryFilterSettings
import reikai.presentation.library.LibraryQuerySource
import reikai.presentation.library.MergeCollapseInputs
import reikai.presentation.library.anyMerged
import reikai.presentation.library.chapterSearchTerms
import reikai.presentation.library.installedIconsBySite
import reikai.presentation.library.libraryBadgePrefsFlow
import reikai.presentation.library.libraryFilterMatches
import reikai.presentation.library.libraryFilterSettingsFlow
import reikai.presentation.library.libraryItemFilterFields
import reikai.presentation.library.libraryItemQueryFields
import reikai.presentation.library.libraryQueryMatches
import reikai.presentation.library.libraryTrackerMeans
import reikai.presentation.library.libraryTracksFlow
import reikai.presentation.library.memberIdsOf
import reikai.presentation.library.mergeCollapseInputsFlow
import reikai.presentation.library.mergedGroupTracks
import reikai.presentation.library.novelSourceBadge
import reikai.presentation.library.sortedByCategoryPref
import reikai.presentation.library.toQueryOverlay
import reikai.presentation.library.withCustomInfo
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.preference.TriState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.track.model.Track
import kotlin.time.Duration.Companion.seconds

/**
 * Drives the novel half of the Library tab: reads favorited novels reactively, shapes
 * each into the shared [LibraryItem], filters them (LibraryEngine buckets and sorts), and exposes the same
 * accessor surface the manga model does so `LibraryTab` can feed either. Mihon's library core is
 * untouched. Selection lives in the shared LibraryEngine, which hands this model the novel ids to act
 * on; display settings are shared with manga, and tracker filter/sort/group reuse the shared tracker
 * machinery via [getNovelTracks].
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class NovelLibraryViewModel(
    private val novelRepository: NovelRepository,
    private val removeNovelsFromLibrary: RemoveNovelsFromLibrary,
    private val setNovelReadStatus: SetNovelReadStatus,
    private val novelChapterRepository: NovelChapterRepository,
    // Deferred on purpose: building the manager restores the persisted queue and resumes the drain,
    // so taking it directly would start novel downloads merely because the library was opened.
    private val novelDownloadManager: () -> NovelDownloadManager,
    private val novelDownloadCache: NovelDownloadCache,
    private val getNovelCategories: GetNovelCategories,
    // Per-entry custom title/cover overrides, overlaid on the displayed rows (display-only).
    private val getCustomNovelInfo: GetCustomNovelInfo,
    private val setNovelCategories: SetNovelCategories,
    private val libraryPreferences: LibraryPreferences,
    private val basePreferences: BasePreferences,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val sourceManager: NovelSourceManager,
    private val mergeManager: NovelMergeManager,
    private val mergeGroupRepository: MergeGroupRepository,
    private val mergedChapterUnitRepository: MergedChapterUnitRepository,
    private val reconcileMergedChapters: ReconcileMergedChapters,
    private val installer: LnPluginInstaller,
    private val trackerManager: TrackerManager,
    private val getNovelTracks: GetNovelTracks,
    private val getNextNovelChapter: GetNextNovelChapter,
    private val novelPreferences: NovelPreferences,
    private val adultContentChecker: AdultContentChecker,
) : ViewModel() {

    private val searchQuery = MutableStateFlow<String?>(null)

    // The novel update restrictions gate the custom-interval axis, as the manga ones do for manga.
    private val filterSettings = libraryFilterSettingsFlow(
        libraryPreferences,
        reikaiLibraryPreferences,
        basePreferences,
        trackerManager,
        updateRestrictions = novelPreferences.novelUpdateRestrictions().changes(),
    )

    /** Null until the first build answers, which the derived state reads as still loading. */
    private val built: StateFlow<State?> =
        combine(
            // No category-table input: grouping, its only reader, is LibraryEngine's, which reads the
            // table itself. Membership still reaches the filter through each row's own categories.
            // Re-emit when sources (un)register so `sourceManager.get(...)` resolves once loaded.
            // The custom-info overlay rides with the library so a title/cover edit re-emits too.
            combine(
                novelRepository.getLibraryNovelAsFlow()
                    .combine(sourceManager.sources) { library, _ -> library }
                    // Re-emit when a download/delete changes the disk index so the badge + filter refresh.
                    .combine(novelDownloadCache.changes) { library, _ -> library },
                getCustomNovelInfo.subscribeAll(),
                // Whole-library novel tracks (novelId -> tracks) ride with the library so a bind/unbind
                // re-sinks the tracker filter/sort/group, read only while one of those uses them.
                libraryTracksFlow(
                    filterSettings,
                    getNovelCategories.subscribe(),
                    libraryPreferences,
                    reikaiLibraryPreferences,
                    getNovelTracks::subscribeAll,
                ),
                ::Triple,
            ),
            // Debounced so a burst of keystrokes rebuilds the list once, matching the manga library.
            // No distinctUntilChanged: a StateFlow already conflates equal values. The resolved
            // `chapter:` id sets ride the slot so the chapter-table scan runs once per query
            // change, not on every library, download-cache or track tick (mirrors the manga side).
            searchQuery.debounce(0.25.seconds).map { query -> query to resolveChapterMatches(query) },
            // The collapse preferences no longer reach this pipeline. They only ever fed grouping,
            // which LibraryEngine owns now, and leaving them in meant every collapse tap rebuilt the
            // whole filtered novel list (merge collapse, tracker scores, filtering) for nothing.
            settingsFlow(),
        ) { (library, customInfo, tracks), search, settings ->
            buildState(library, customInfo, tracks, search, settings)
        }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    val state: StateFlow<State> = combine(built, searchQuery) { built, searchQuery ->
        // The query comes from its own holder, never from [built]: that lags the user by a debounce plus
        // a query, so taking its copy resets the search field to a stale value mid-input and scrambles
        // fast keystrokes. The selection is not here at all; the engine owns it.
        (built ?: State()).copy(searchQuery = searchQuery)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    init {
        // Load the plugin host so the library can resolve each novel's source (lang + source-icon
        // badges); the source flow above re-emits the build once the sources register.
        viewModelScope.launchIO { runCatchingCancellable { installer.ensureLoaded() } }
        // A newly grouped entry's chapters have no cross-source identities yet, so the deduplicated
        // unread count would be wrong until something wrote them. Reconciling off the membership flow
        // covers every merge and unmerge from one place. The membership query re-runs on every write to
        // the novels table, at the cost of one pass over the grouped novels, and a reconcile runs only
        // when the answer changed. Stays always-on: a restore can regroup entries while the library
        // renders nothing. The preferred-source list rides along, since it picks each group's trunk.
        viewModelScope.launchIO {
            stitchInputChanges(
                ContentType.NOVELS,
                mergeGroupRepository,
                reikaiLibraryPreferences.preferredNovelSources.changes(),
            )
                .collectLatest { reconcileMergedChapters.await() }
        }
    }

    /** Folds the badge and filter prefs into one flow so the main combine stays at its 5-arg max. Sorting
     *  is LibraryEngine's, so no sort input rides here: it would rebuild this list for nothing. */
    private fun settingsFlow(): Flow<LibrarySettings> {
        val mergeFlow = mergeCollapseInputsFlow(
            ContentType.NOVELS,
            reikaiLibraryPreferences.preferredNovelSources.changes(),
            reikaiLibraryPreferences,
            mergeGroupRepository,
            mergedChapterUnitRepository,
        )
        // No group-by input: grouping is LibraryEngine's, and re-running this whole pipeline on a
        // group-mode change would rebuild the filtered list for a decision it no longer makes.
        return combine(
            libraryBadgePrefsFlow(libraryPreferences, reikaiLibraryPreferences),
            libraryPreferences.showContinueReadingButton.changes(),
            filterSettings,
            mergeFlow,
        ) { badges, showContinue, filter, merge -> LibrarySettings(badges, showContinue, filter, merge) }
    }

    /** One chapter-table LIKE scan per distinct `chapter:` term, resolved once per query change. */
    private suspend fun resolveChapterMatches(query: String?): Map<String, Set<Long>> {
        val node = query?.takeUnless { it.isBlank() }?.let(QueryNode::from) ?: return emptyMap()
        return node.chapterSearchTerms().associateWith { novelChapterRepository.getNovelIdsWithChapterNameLike(it) }
    }

    /**
     * Merged chapters with a copy on disk, per group. Members that have downloaded nothing are never
     * probed, so a library whose merged novels hold no downloads pays nothing here at all. Twin of the
     * manga library's, over the same kernel.
     */
    private fun mergedDownloadCounts(
        library: List<LibraryNovel>,
        rowsByGroup: Map<Long, List<DownloadUnitRow>>,
    ): Map<Long, Int> {
        val withDownloads = library.filter { it.downloadCount > 0 }
        if (withDownloads.isEmpty()) return emptyMap()
        val novelById = withDownloads.associate { it.novel.id to it.novel }
        return downloadedUnitsByGroup(
            rowsByGroup = rowsByGroup,
            ownersWithDownloads = novelById.keys,
        ) { row ->
            val owner = novelById.getValue(row.ownerId)
            novelDownloadCache.isChapterDownloaded(owner.source, owner.title, row.chapterName, row.chapterUrl)
        }
    }

    private suspend fun buildState(
        library: List<LibraryNovel>,
        customInfo: List<CustomNovelInfo>,
        tracks: Map<Long, List<NovelTrack>>,
        search: Pair<String?, Map<String, Set<Long>>>,
        settings: LibrarySettings,
    ): State {
        val (query, chapterMatches) = search
        // Downloaded state is disk-derived (NovelDownloadCache), not a DB column, so fill each novel's
        // download count from the cache before it feeds the filter, sort, collapse, and badge.
        val withCounts = library.map {
            it.copy(downloadCount = novelDownloadCache.getDownloadCount(it.novel).toLong())
        }
        // Collapse merged groups into one representative entry (the top-ranked source) BEFORE
        // filtering, matching the manga library. Filtering first would test each source separately, so a
        // group could survive on a member the user never sees, and the representative would be picked
        // from whichever members happened to pass, changing the cover as filters change.
        // Each group's unread and downloads are the deduplicated cross-source counts: one unit per chapter
        // the group covers, which summing the members double-counted for every chapter two of them hold.
        val groups = NovelMergeCollapse.collapse(
            withCounts,
            settings.merge.membership,
            settings.merge.mergingEnabled,
            settings.merge.overrideRankings,
            settings.merge.preferredSources,
            mergedCountsByGroup = settings.merge.mergedCounts,
            mergedDownloadsByGroup = if (settings.merge.mergingEnabled) {
                mergedDownloadCounts(withCounts, settings.merge.downloadUnits)
            } else {
                emptyMap()
            },
        )
        // Each merge group's tracks keyed by the rep's real novel id, so the filter's tracker axis and the
        // grouping read a track bound on ANY grouped source. Synchronous: reads the in-memory group members,
        // never the suspend awaitGroup. Converted once to the shared Track, which the track kernels take.
        val loggedInTrackerIds = settings.filter.trackers.keys
        val uiTracks = tracks.mapValues { (_, novelTracks) -> novelTracks.map { it.toUiTrack() } }
        val membersByRep = groups.associate { it.representative.novel.id to it.memberIds }
        val tracksByRep = membersByRep.mapValues { (_, memberIds) -> mergedGroupTracks(memberIds, uiTracks) }
        val trackerMeanScores = libraryTrackerMeans(
            membersByRow = membersByRep,
            tracksById = uiTracks,
            trackers = trackerManager.getAll(loggedInTrackerIds).associateBy { it.id },
        )
        // The one shared library filter (tracker axis folded in), so a filter change reaches manga and
        // novels at once. The per-type seams live in the accessors: novels have no local-source concept.
        val filterPrefs = settings.filter.resolve()
        val iconsBySite = installedIconsBySite(sourceManager.getAll())
        // Keyed by the representative's novel id (== the LibraryItem id). The dynamic grouping resolves
        // per-novel metadata (genre / author / source / status) the row cannot carry, and the search
        // needs the source name and slug, since a novel row has no Mihon Source to read either off.
        val novelById = groups.associate { it.representative.novel.id to it.representative }
        // Display-only custom-info overlay, keyed by the real novel id. Carried into the state and applied
        // at the display read (State.withOverlay, via LibraryProvider.overlaid), never here, so collapse,
        // filter, sort, grouping and search all keep reading the source values. Mirrors the manga library.
        val overlay = customInfo.associateBy { it.novelId }
        // Build the shared library row BEFORE filtering and sorting, so both content types reach the
        // shared kernels at the same point in the type chain (the manga library already builds first).
        val allItems = groups.map { group ->
            // An uninstalled source still gets a badge, as a manga group keeps its stub member.
            group.toLibraryRow(settings.badges, settings.merge.showSourceIcons, ::querySource) {
                novelSourceBadge(sourceManager.get(it), iconsBySite)
            }
        }
        // The one filter binding both libraries use; the adult rule's source-name list is manga sites.
        val adultSources = if (filterPrefs.lewd != TriState.DISABLED) {
            adultContentChecker.libraryAdultNovelSources(novelById.values.mapTo(mutableSetOf()) { it.novel.source })
        } else {
            emptySet()
        }
        val filterFields = libraryItemFilterFields(
            adultSource = { novelById[it.id]?.novel?.source in adultSources },
            lewdSourceName = { null },
            trackerIds = { item -> tracksByRep[item.id].orEmpty().map { it.trackerId } },
        )
        // The search twin of the filter binding above, and the same kernel the manga library runs, so one
        // typed query means one thing on every row of the All list. The seam: a novel's source key is its
        // plugin slug (manga supply a numeric id).
        val queryNode = query?.takeUnless { it.isBlank() }?.let(QueryNode::from)
        val queryFields = libraryItemQueryFields(
            sourceKey = { item -> novelById[item.id]?.novel?.source.orEmpty() },
            chapterMatches = chapterMatches,
            // Search matches what the card shows, so a renamed novel is findable by the name you gave it.
            // The rows stay override-free: filter, sort and grouping deliberately read the source values.
            overlay = overlay.mapValues { (_, custom) -> custom.toQueryOverlay() },
        )
        val items = allItems.filter { item ->
            val matchesSearch = queryNode == null || libraryQueryMatches(queryNode, item, queryFields)
            matchesSearch && libraryFilterMatches(item, filterPrefs, filterFields)
        }
        val byId = items.associateBy { it.id }
        // Bucketing, category order and sorting all moved to LibraryEngine's shared assembly, which sees
        // both content types. This model stops at the filtered rows, its split point.

        // Item id -> (source, url) so LibraryTab can open the (representative) novel. Over the displayed
        // rows, so the hopper's random actions pick from what is actually on screen.
        val routes = items.mapNotNull { item ->
            val novel = novelById[item.id]?.novel ?: return@mapNotNull null
            item.id to NovelRoute(novel.source, novel.url)
        }.toMap()

        return State(
            isLoading = false,
            searchQuery = query,
            favorites = items,
            trackerMeans = trackerMeanScores,
            novelById = novelById,
            tracksByRep = tracksByRep,
            favoritesById = byId,
            customInfo = overlay,
            novelRoutes = routes,
            hasActiveFilters = settings.filter.isActive,
            showContinueButton = settings.showContinue,
        )
    }

    // --- search ---

    fun search(query: String?) {
        searchQuery.value = query
    }

    // --- multi-select actions ---

    /** Manually merge the selected novels into one group (covers both library views). */
    fun mergeSelection(ids: List<Long>) {
        if (ids.size < 2) return
        viewModelScope.launchIO {
            // each selected card's whole group is absorbed by the merge, so one call coalesces every source
            mergeManager.merge(ids)
        }
    }

    /** Split the selected novels out of their merge groups (no-op for non-merged selections).
     *  The manager hands each member its own tracker copy on the way out. */
    fun unmergeSelection(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launchIO { mergeManager.unmerge(ids) }
    }

    fun markReadSelection(ids: List<Long>, read: Boolean) {
        // Mark every source of a merge group, so a merged series doesn't stay part-read on the
        // sources that aren't the representative.
        val novelIds = state.value.memberIdsFor(ids)
        // Non-cancellable like the manga twins: a bulk write must not half-apply because the
        // screen was left mid-loop.
        viewModelScope.launchNonCancellable {
            // The interactor groups by novel for delete-after-read, so pass every selected novel's chapters.
            val chapters = novelIds.flatMap { novelChapterRepository.getByNovelId(it) }
            setNovelReadStatus.await(read, chapters)
        }
    }

    fun performDownloadAction(ids: List<Long>, action: DownloadAction) {
        // NOT expanded over the group's members: they carry the same chapters, so downloading every
        // member would fetch each chapter once per source and waste the storage on near-duplicates.
        // The target is the group's deduplicated list, the one the details "All" view shows, with each
        // chapter fetched from the source that carries it.
        viewModelScope.launchNonCancellable {
            ids.forEach { id ->
                val group = getNextNovelChapter.groupChapters(id)
                val downloadManager = novelDownloadManager()
                // Probed over every member's chapters: a chapter downloaded on any of them is on disk,
                // whichever copy the stitch shows.
                val novelsById = novelRepository.ownersOf(group.pooledChapters)
                val flags = group.groupFlags { novelDownloadCache.downloadedChapterIds(it, novelsById) }
                val queuedIds = downloadManager.queueState.value.mapTo(HashSet()) { it.chapterId }
                // The interactor already hands them over in reading order.
                val targets = DownloadCandidates.forGroup(
                    group.chapters,
                    action,
                    flags,
                    getNextNovelChapter.hiddenAmong(group.pooledChapters),
                ) { it.id in queuedIds }
                if (targets.isNotEmpty()) downloadManager.downloadChapters(targets)
            }
        }
    }

    /** Writes exactly the ids it is handed; the caller expands the merge group. */
    fun setNovelCategories(novelIds: List<Long>, addCategories: List<Long>, removeCategories: List<Long>) {
        viewModelScope.launchNonCancellable {
            novelIds.forEach { novelId ->
                val current = getNovelCategories.awaitByNovelId(novelId).map { it.id }
                val new = (current - removeCategories.toSet() + addCategories).distinct()
                setNovelCategories.await(novelId, new)
            }
        }
    }

    fun removeNovels(
        novelIds: List<Long>,
        deleteFromLibrary: Boolean,
        deleteDownloads: Boolean,
        // Expand merged covers to every grouped source, so the whole series leaves the library.
        removeGroupedSources: Boolean = false,
    ) {
        viewModelScope.launchNonCancellable {
            val targets = if (removeGroupedSources) state.value.memberIdsFor(novelIds) else novelIds
            if (deleteFromLibrary) removeNovelsFromLibrary.await(targets)
            targets.forEach { novelId ->
                // The whole entry's downloads, as manga's library removal deletes: the chapter delete
                // would keep bookmarked chapters behind a series the user asked to clear out.
                if (deleteDownloads) {
                    novelRepository.getById(novelId)?.let { novelDownloadManager().awaitDeleteNovel(it) }
                }
            }
        }
    }

    /** The next-unread chapter to resume. For a merged novel this pools the whole group (the unified
     *  cross-source list the details "All" view shows) to find the first unread, narrowed by the novel's
     *  own chapter filters; the reader resolves the group order for prev/next, so only the chapter is returned. */
    suspend fun getResume(repNovelId: Long): NovelChapter? =
        getNextNovelChapter.awaitFirstUnreadInGroup(
            repNovelId,
            downloadedOnly = basePreferences.downloadedOnly.get(),
            downloadedIds = novelDownloadCache::downloadedChapterIds,
        )

    // --- settings sheet: LibraryEngine builds the library-wide part, this model only lists categories ---

    /** Every category the novel library can file into (the system Default, universal and novel-only rows) in
     *  the category sort order, for the settings sheet's category filter and per-category sort. Empty
     *  categories stay in, since a filter or a sort can be set on one before anything is filed there. */
    val filterPickerCategories: StateFlow<List<Category>> = getNovelCategories.subscribe()
        .sortedByCategoryPref(reikaiLibraryPreferences)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    /** A novel's human-readable source name for search, by the one rule [NovelSourceManager.nameOf] keeps. */
    private suspend fun novelSourceName(source: String): String = sourceManager.nameOf(source)

    // One novel source as the search terms read it, for the row's own source and every merged member's, so
    // the two cannot resolve a name or language differently.
    private suspend fun querySource(source: String) = LibraryQuerySource(
        key = source,
        name = novelSourceName(source).lowercase(),
        language = sourceManager.langOf(source),
        isLocal = false,
    )

    private data class LibrarySettings(
        val badges: LibraryBadgePrefs,
        val showContinue: Boolean,
        val filter: LibraryFilterSettings,
        val merge: MergeCollapseInputs<String>,
    )

    data class State(
        val isLoading: Boolean = true,
        val searchQuery: String? = null,
        val hasActiveFilters: Boolean = false,
        val showContinueButton: Boolean = false,
        /** The filtered, merge-collapsed rows before bucketing and sort, in pipeline order; the novel
         *  split point the provider's row flow reads (the twin of manga's LibraryData.favorites). The
         *  custom-info overlay is NOT applied here, matching the manga contract. */
        val favorites: List<LibraryItem> = emptyList(),
        /** Per-rep mean tracker score (0-10, unscored reps absent), for the sort and the provider seam
         *  (LibraryProvider.trackerMeans). */
        val trackerMeans: Map<Long, Double> = emptyMap(),
        /** Rep id -> its LibraryNovel and unioned merge-group tracks, carried for the dynamic-grouping
         *  feed seam (LibraryProvider.dynamicGroupingFeed), which resolves metadata the row cannot carry. */
        val novelById: Map<Long, LibraryNovel> = emptyMap(),
        val tracksByRep: Map<Long, List<Track>> = emptyMap(),
        private val favoritesById: Map<Long, LibraryItem> = emptyMap(),
        /** Display-only overrides, keyed by real novel id; applied at the display read only. */
        private val customInfo: Map<Long, CustomNovelInfo> = emptyMap(),
        private val novelRoutes: Map<Long, NovelRoute> = emptyMap(),
    ) {
        /** Identity of [customInfo], so a display-overlay edit is not conflated away downstream. */
        val overlayKey: Any get() = customInfo

        val isLibraryEmpty = favoritesById.isEmpty()

        // These resolve an explicit id set rather than reading the selection, so a bulk action is driven
        // by the ids its caller passes. That is what lets the shared engine own a selection spanning both
        // content types and hand each provider only its own ids. Mirrors the manga library.

        /** Any of [ids] is a merge group (drives the bulk Unmerge action). */
        fun containsMerged(ids: Collection<Long>): Boolean = favoritesById.anyMerged(ids)

        /** Every grouped source-novel behind [ids]. */
        fun memberIdsFor(ids: Collection<Long>): List<Long> = favoritesById.memberIdsOf(ids)

        /**
         * The one place the overlay is applied, reached through the provider seam
         * (LibraryProvider.overlaid) at the shared assembly's display read, so the overrides never reach
         * the raw rows that filter, sort and search read. Mirrors the manga library.
         */
        fun withOverlay(item: LibraryItem): LibraryItem = item.withCustomInfo(customInfo[item.id])

        /** (source, url) for the item id, to open the novel details screen. */
        fun routeFor(itemId: Long): NovelRoute? = novelRoutes[itemId]
    }

    data class NovelRoute(val source: String, val url: String)
}
