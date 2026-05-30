package yokai.presentation.library.manga

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.CategoryImpl
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.seriesType
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.library.LibraryGroup
import eu.kanade.tachiyomi.ui.library.LibrarySort
import eu.kanade.tachiyomi.ui.library.filter.FilterBottomSheet
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.chapter.ChapterSort
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.mapStatus
import eu.kanade.tachiyomi.util.system.launchIO
import yokai.i18n.MR
import yokai.util.lang.getString
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.injectLazy
import yokai.domain.category.interactor.GetCategories
import yokai.domain.category.interactor.UpdateCategories
import yokai.domain.category.models.CategoryUpdate
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.chapter.interactor.UpdateChapter
import yokai.domain.library.LibraryPreferences
import yokai.domain.manga.interactor.GetLibraryManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.track.interactor.DeleteTrack
import yokai.domain.track.interactor.GetTrack
import yokai.domain.track.interactor.InsertTrack
import yokai.domain.ui.UiPreferences
import yokai.presentation.library.LibraryTabState
import yokai.presentation.library.manga.actions.DownloadAction
import yokai.presentation.library.manga.actions.MangaLibraryActions
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlin.random.Random

/**
 * Compose library screen model. Collects categories, library manga, the download-cache
 * invalidation flow, the [LibraryUpdateJob] running flag, and the `lastUsedCategory`
 * preference; folds them through [MangaLibrarySectioner] and surfaces [LibraryTabState] to
 * the UI.
 *
 * `updateFlow` is subscribed in a sibling block: on its `null` (completion) emission we
 * re-derive `inQueueCategoryIds` because [LibraryUpdater.isRunningFlow] only flips when the
 * LAST manga finishes, but `categoryInQueue` may drop individual categories mid-update.
 * The `STARTING_UPDATE_SOURCE` sentinel and real manga ids are ignored: per-row state
 * already rides on `getLibraryManga.subscribe()` re-emissions when the DB changes.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class MangaLibraryScreenModel :
    StateScreenModel<LibraryTabState<LibraryItem.Manga, Category>>(LibraryTabState.Loading) {

    private val preferences: PreferencesHelper by injectLazy()
    private val libraryPreferences: LibraryPreferences by injectLazy()
    private val getCategories: GetCategories by injectLazy()
    private val getLibraryManga: GetLibraryManga by injectLazy()
    private val downloadCache: DownloadCache by injectLazy()
    private val libraryUpdater: MangaLibraryUpdater by injectLazy()
    private val sourceManager: SourceManager by injectLazy()
    private val downloadManager: DownloadManager by injectLazy()
    private val getChapter: GetChapter by injectLazy()
    private val updateChapter: UpdateChapter by injectLazy()
    private val updateManga: UpdateManga by injectLazy()
    private val deleteTrack: DeleteTrack by injectLazy()
    private val coverCache: CoverCache by injectLazy()
    private val getTrack: GetTrack by injectLazy()
    private val insertTrack: InsertTrack by injectLazy()
    private val updateCategories: UpdateCategories by injectLazy()
    private val trackManager: TrackManager by injectLazy()
    private val uiPreferences: UiPreferences by injectLazy()

    /**
     * Library search query. Owned here (rather than as composable state) so the search + filter
     * pipeline can re-derive [LibraryTabState.Loaded.filteredLibrary] reactively off it. The
     * composable collects [searchQuery] for its text field and pushes edits via [setSearchQuery].
     */
    private val searchQueryFlow = MutableStateFlow("")
    val searchQuery: StateFlow<String> = searchQueryFlow.asStateFlow()

    fun setSearchQuery(query: String) {
        searchQueryFlow.value = query
    }

    /**
     * Pre-collapse libraryManga snapshot used by [selectedMangaListWithMergedSiblings] to
     * resolve merge-group sibling [Manga] objects that [MangaLibraryGrouping.collapse] dropped
     * from the rendered library (only leaders survive there). Updated alongside every state
     * emission below. Kept off [LibraryTabState] because no composable consumes it; it's a
     * screen-model-internal concern. Mirrors legacy
     * `presenter.getLibraryMangaById(it)?.manga` at `LibraryController.kt:2234` (delete) /
     * `:2277` (move).
     */
    private var libraryMangaForResolve: List<LibraryManga> = emptyList()

    init {
        screenModelScope.launchIO {
            val rawFlow = combine(
                getCategories.subscribe(),
                getLibraryManga.subscribe(),
                downloadCache.changes,
                libraryUpdater.isRunningFlow(),
                preferences.lastUsedCategory().changes(),
            ) { categories, libraryManga, _, isRunning, currentCategoryOrder ->
                // Empty merge / unmerge sets are placeholders; the chained .combine() below
                // overwrites both before the snapshot reaches collectLatest (combine waits for
                // every upstream flow before producing).
                Snapshot(
                    categories = categories,
                    libraryManga = libraryManga,
                    isRunning = isRunning,
                    currentCategoryOrder = currentCategoryOrder,
                    manualMerges = emptySet(),
                    manualUnmerges = emptySet(),
                    autoMergeSameTitle = true,
                    sortPrefs = SortPrefs.DEFAULT,
                    categorySortOrder = 0,
                    groupingPrefs = GroupingPrefs.DEFAULT,
                )
            }
                // The 5-arg combine is the kotlinx-coroutines typed-lambda limit. Chain the
                // grouping prefs in via binary .combine() so the screen reactively re-collapses
                // merge groups when the user merges / unmerges from the action bar (legacy
                // mutates these prefs without ripple-updating the DB, so a getLibraryManga
                // re-emission isn't guaranteed). The autoMergeSameTitle toggle is also live-
                // observed so flipping the Display sheet switch re-renders immediately. The
                // sort prefs are bundled into a single SortPrefs flow to keep the chain shallow.
                .combine(preferences.mangaManualMerges().changes()) { snap, merges ->
                    snap.copy(manualMerges = merges)
                }
                .combine(preferences.mangaManualUnmerges().changes()) { snap, unmerges ->
                    snap.copy(manualUnmerges = unmerges)
                }
                .combine(preferences.autoMergeSameTitle().changes()) { snap, autoMerge ->
                    snap.copy(autoMergeSameTitle = autoMerge)
                }
                .combine(sortPrefsFlow()) { snap, sortPrefs ->
                    snap.copy(sortPrefs = sortPrefs)
                }
                // categorySortOrder is Reikai-fork (per-library, not per-category). Chained
                // separately from sortPrefsFlow since it shapes the category-list order, not
                // the in-category manga sort.
                .combine(preferences.categorySortOrder().changes()) { snap, categorySortOrder ->
                    snap.copy(categorySortOrder = categorySortOrder)
                }
                // Dynamic grouping prefs: the group-by mode, the collapsed-dynamic-categories
                // set, and the collapsed-at-bottom toggle. Bundled into a single combine so the
                // main chain stays shallow.
                .combine(groupingPrefsFlow()) { snap, groupingPrefs ->
                    snap.copy(groupingPrefs = groupingPrefs)
                }
                .mapLatest { snap ->
                    // Build the rendered library map. Branches per groupLibraryBy:
                    //   BY_DEFAULT: sectioner (by Category) -> merge collapse -> per-cat sort.
                    //   Dynamic (BY_SOURCE / BY_LANGUAGE / BY_TAG / BY_AUTHOR / BY_STATUS /
                    //     BY_TRACK_STATUS): pre-resolve per-manga metadata -> dynamic grouping
                    //     builds synthetic categories -> sort items within each synthetic cat.
                    val library: Map<Category, List<LibraryItem.Manga>> =
                        if (snap.groupingPrefs.groupLibraryBy == LibraryGroup.BY_DEFAULT) {
                            // Recreate per emission so a locale change between subscriptions picks
                            // up the re-translated "Default" string, matching legacy behavior.
                            // The default category's sort lives in `preferences.defaultMangaOrder`
                            // rather than the categories table; legacy reads it at
                            // LibraryPresenter.kt:1320-1322.
                            val defaultCategory = Category.createDefault(preferences.context)
                                .apply {
                                    order = -1
                                    val defOrder = snap.sortPrefs.defaultMangaOrder
                                    if (defOrder.firstOrNull()?.isLetter() == true) {
                                        mangaSort = defOrder.first()
                                    }
                                }
                            val sectioned = MangaLibrarySectioner.section(
                                libraryManga = snap.libraryManga,
                                userCategories = snap.categories,
                                defaultCategory = defaultCategory,
                                categorySortOrder = snap.categorySortOrder,
                            )
                            // Collapse merged-manga groups (manual merge + same-title auto-merge)
                            // into a single rendered entry per group, stamped with relatedMangaIds.
                            // Mirrors legacy LibraryPresenter.applySourceGrouping at
                            // LibraryPresenter.kt:943-997.
                            val grouped = MangaLibraryGrouping.collapse(
                                library = sectioned,
                                manualMerges = snap.manualMerges,
                                manualUnmerges = snap.manualUnmerges,
                                autoMergeSameTitle = snap.autoMergeSameTitle,
                            )
                            // Per-category sort (9 modes), with library-wide default as fallback.
                            MangaLibrarySort.sort(
                                library = grouped,
                                libraryDefaultMode = snap.sortPrefs.mode,
                                libraryDefaultAscending = snap.sortPrefs.ascending,
                                randomSeed = snap.sortPrefs.randomSeed,
                                removeArticles = snap.sortPrefs.removeArticles,
                            )
                        } else {
                            // Dynamic grouping. Pre-resolve per-manga metadata that the legacy
                            // resolves inline inside getDynamicLibraryItems at
                            // LibraryPresenter.kt:1128-1296. Suspends only when the active group
                            // type actually needs tracker data, so the common BY_SOURCE /
                            // BY_TAG / BY_AUTHOR / BY_STATUS / BY_LANGUAGE paths stay synchronous.
                            val groupType = snap.groupingPrefs.groupLibraryBy
                            val unknownLabel = preferences.context.getString(MR.strings.unknown)
                            val notTrackedLabel =
                                preferences.context.getString(MR.strings.not_tracked)

                            val sourceMeta = if (groupType == LibraryGroup.BY_SOURCE) {
                                snap.libraryManga.mapNotNull { lm ->
                                    val id = lm.manga.id ?: return@mapNotNull null
                                    val source = sourceManager.getOrStub(lm.manga.source)
                                    id to (source.name to source.id)
                                }.toMap()
                            } else emptyMap()

                            val languageCodes = if (groupType == LibraryGroup.BY_LANGUAGE) {
                                snap.libraryManga.mapNotNull { lm ->
                                    val id = lm.manga.id ?: return@mapNotNull null
                                    val lang = languageOf(lm.manga) ?: return@mapNotNull null
                                    id to lang
                                }.toMap()
                            } else emptyMap()

                            val statusNames = if (groupType == LibraryGroup.BY_STATUS) {
                                snap.libraryManga.mapNotNull { lm ->
                                    val id = lm.manga.id ?: return@mapNotNull null
                                    id to preferences.context.mapStatus(lm.manga.status)
                                }.toMap()
                            } else emptyMap()

                            val trackStatuses = if (groupType == LibraryGroup.BY_TRACK_STATUS) {
                                val loggedServices =
                                    trackManager.services.filter { it.isLogged }
                                snap.libraryManga.mapNotNull { lm ->
                                    val mangaId = lm.manga.id ?: return@mapNotNull null
                                    val tracks = getTrack.awaitAllByMangaId(mangaId)
                                    val track = tracks.find { t ->
                                        loggedServices.any { it.id == t.sync_id }
                                    }
                                    val service =
                                        loggedServices.find { it.id == track?.sync_id }
                                    if (track != null && service != null) {
                                        val status = if (loggedServices.size > 1) {
                                            service.getGlobalStatus(track.status)
                                        } else {
                                            service.getStatus(track.status)
                                        }
                                        mangaId to status
                                    } else null
                                }.toMap()
                            } else emptyMap()

                            val dynamic = MangaLibraryDynamicGrouping.build(
                                libraryManga = snap.libraryManga,
                                groupType = groupType,
                                librarySortingMode = snap.sortPrefs.mode.mainValue,
                                librarySortingAscending = snap.sortPrefs.ascending,
                                collapsedDynamicCategories =
                                    snap.groupingPrefs.collapsedDynamicCategories,
                                collapsedDynamicAtBottom =
                                    snap.groupingPrefs.collapsedDynamicAtBottom,
                                unknownLabel = unknownLabel,
                                notTrackedLabel = notTrackedLabel,
                                sourceMeta = sourceMeta,
                                trackStatuses = trackStatuses,
                                languageCodes = languageCodes,
                                statusNames = statusNames,
                                languageDisplay = ::languageDisplay,
                                trackingStatusOrder = ::mapTrackingOrder,
                            )

                            // Sort manga within each synthetic category. createCustom already set
                            // each category's mangaSort to the library-wide default, so the sort
                            // helper reads that per-category mode.
                            MangaLibrarySort.sort(
                                library = dynamic,
                                libraryDefaultMode = snap.sortPrefs.mode,
                                libraryDefaultAscending = snap.sortPrefs.ascending,
                                randomSeed = snap.sortPrefs.randomSeed,
                                removeArticles = snap.sortPrefs.removeArticles,
                            )
                        }
                    val inQueue = if (snap.isRunning) {
                        library.keys.mapNotNullTo(HashSet()) { cat ->
                            cat.id?.takeIf { libraryUpdater.isCategoryInQueue(it) }
                        }
                    } else {
                        emptySet()
                    }
                    libraryMangaForResolve = snap.libraryManga
                    // Search/filter inputs (precomputed off the raw library): source names and
                    // series types feed MangaLibrarySearch; logged-service names feed the filter
                    // and the filter sheet's Tracker row; detectedTypes feeds the Series type row.
                    val items = library.values.asSequence().flatten()
                    val sourceNames = items
                        .map { it.libraryManga.manga.source }
                        .distinct()
                        .associateWith { sourceManager.getOrStub(it).name }
                    val seriesTypes = items
                        .mapNotNull { item ->
                            val m = item.libraryManga.manga
                            val id = m.id ?: return@mapNotNull null
                            id to m.seriesType(preferences.context, sourceManager)
                        }
                        .toMap()
                    val detectedTypes = MangaLibraryFilter.detectMangaTypes(library, sourceManager)
                    val loggedServiceNames = trackManager.services
                        .filter { it.isLogged }
                        .associate { it.id to preferences.context.getString(it.nameRes()) }
                    // Stamp the on-disk download count onto each item so the cover download badge
                    // renders a real number. The cells gate display on the showDownloadBadge pref;
                    // getDownloadCount is an in-memory DownloadCache lookup, and downloadCache.changes
                    // is already a combine input upstream, so the badge updates live.
                    val libraryWithDownloads = library.mapValues { (_, mangaItems) ->
                        mangaItems.map { it.copy(downloadCount = downloadManager.getDownloadCount(it.libraryManga.manga).toLong()) }
                    }
                    RawData(
                        library = libraryWithDownloads,
                        isRunning = snap.isRunning,
                        inQueue = inQueue,
                        currentCategoryOrder = snap.currentCategoryOrder,
                        categorySortOrder = snap.categorySortOrder,
                        collapsedDynamicCategories = snap.groupingPrefs.collapsedDynamicCategories,
                        collapsedDynamicAtBottom = snap.groupingPrefs.collapsedDynamicAtBottom,
                        sourceNames = sourceNames,
                        seriesTypes = seriesTypes,
                        detectedTypes = detectedTypes,
                        loggedServiceNames = loggedServiceNames,
                    )
                }
                // Collapse identical rebuilds. The combine upstream re-emits on every
                // downloadCache tick / library re-subscribe / isRunning flip; without this the
                // suspend filter (track I/O) re-ran dozens of times on unchanged data after a
                // search settled (the "laggy for a second" burst). RawData is a data class and
                // LibraryItem.Manga has value equality, so equal rebuilds compare equal and drop
                // here, restoring the original produceState-keyed-on-library gating.
                .distinctUntilChanged()
                // Collapse rebuild storms. getLibraryManga / downloadCache emit in rapid bursts
                // (e.g. an active filter's getDownloadCount renews the cache, which notifies, which
                // rebuilds), and with a filter active each rebuild re-runs the track/download I/O.
                // Debounce so a storm collapses to one filter pass once it settles. Only the raw
                // library is debounced; the search query (a separate flow) stays instant.
                .debounce(250L)

            // Search + filter run downstream of the raw-library build, gated so the suspend
            // filter (track / download I/O) only re-runs when the raw library OR a filter input
            // changes, never on a display/badge pref change (those land in a separate flow).
            combine(rawFlow, filterInputsFlow().distinctUntilChanged()) { raw, inputs -> raw to inputs }
                .mapLatest { (raw, inputs) ->
                    val searched = MangaLibrarySearch.search(
                        raw.library,
                        inputs.query,
                        raw.sourceNames,
                        raw.seriesTypes,
                    )
                    val filtered = if (inputs.filterState.isAnyActive) {
                        MangaLibraryFilter.filter(
                            library = searched,
                            state = inputs.filterState,
                            sourceManager = sourceManager,
                            loggedServiceNames = raw.loggedServiceNames,
                            getDownloadCount = { manga -> downloadManager.getDownloadCount(manga) },
                            getTracks = { mangaId -> getTrack.awaitAllByMangaId(mangaId) },
                            keepEmptyCategories = inputs.showAllCategories,
                        )
                    } else {
                        searched
                    }
                    Rendered(raw, inputs.filterState.isAnyActive, filtered)
                }
                // Display prefs join AFTER the filter stage: a cosmetic change re-emits with the
                // last (cached) Rendered, so the render updates without re-running the filter.
                .combine(displayPrefsFlow()) { rendered, display -> rendered to display }
                .collectLatest { (rendered, display) ->
                    // Preserve selection across reload emissions so a download-cache tick or
                    // library update mid-action doesn't drop the user's selection set. Also
                    // carry sortEpoch forward unchanged; combine-driven emissions never bump it
                    // (only optimistic sort writes do, see setSort). categorySortOrder AND the
                    // collapsed-dynamic fields are reflected into state so a pref change visibly
                    // re-emits even when the library map compares equal to its previous value
                    // (see LibraryTabState.Loaded KDoc for the CategoryImpl.equals / Map.equals
                    // gotcha).
                    mutableState.update { current ->
                        val raw = rendered.raw
                        val loaded = current as? LibraryTabState.Loaded
                        LibraryTabState.Loaded(
                            library = raw.library,
                            totalItemCount = raw.library.values.sumOf { it.size },
                            isRunning = raw.isRunning,
                            inQueueCategoryIds = raw.inQueue,
                            currentCategoryOrder = raw.currentCategoryOrder,
                            selection = loaded?.selection ?: emptySet(),
                            sortEpoch = loaded?.sortEpoch ?: 0,
                            categorySortOrder = raw.categorySortOrder,
                            collapsedDynamicCategories = raw.collapsedDynamicCategories,
                            collapsedDynamicAtBottom = raw.collapsedDynamicAtBottom,
                            filteredLibrary = rendered.filteredLibrary,
                            detectedTypes = raw.detectedTypes,
                            loggedTrackerNames = raw.loggedServiceNames.values.toList(),
                            isAnyFilterActive = rendered.isAnyFilterActive,
                            libraryLayout = display.layout.libraryLayout,
                            uniformGrid = display.layout.uniformGrid,
                            useStaggeredGrid = display.layout.useStaggeredGrid,
                            gridSize = display.layout.gridSize,
                            outlineOnCovers = display.layout.outlineOnCovers,
                            showDownloadBadge = display.badge.showDownloadBadge,
                            showLanguageBadge = display.badge.showLanguageBadge,
                            unreadBadgeType = display.badge.unreadBadgeType,
                            hideStartReadingButton = display.badge.hideStartReadingButton,
                            showCategoryInTitle = display.misc.showCategoryInTitle,
                            showCategoryItemCounts = display.misc.showCategoryItemCounts,
                            hideHopper = display.hopper.hideHopper,
                            autohideHopper = display.hopper.autohideHopper,
                            hopperGravity = display.hopper.hopperGravity,
                            hopperLongPressAction = display.hopper.hopperLongPressAction,
                            groupLibraryBy = display.category.groupLibraryBy,
                            collapsedCategories = display.category.collapsedCategories,
                            showAllCategories = display.category.showAllCategories,
                            showEmptyCategoriesWhileFiltering = display.category.showEmptyCategoriesWhileFiltering,
                            lastUsedCategoryOrder = display.category.lastUsedCategoryOrder,
                            manualMerges = display.misc.manualMerges,
                        )
                    }
                }
        }

        // updateFlow's null emission signals job completion: kick a re-derivation of the in-queue
        // set so headers stop spinning the moment WorkManager flips isRunning -> false. Mid-update
        // category drops are handled by the isRunningFlow path via the combine above (each tick
        // re-walks categoryInQueue). Real manga ids and the -5L sentinel don't affect UI state we
        // own; the existing getLibraryManga subscription handles row data refresh.
        screenModelScope.launchIO {
            libraryUpdater.updateFlow.collectLatest { mangaId ->
                if (mangaId == null) {
                    mutableState.update { current ->
                        if (current is LibraryTabState.Loaded) {
                            current.copy(inQueueCategoryIds = emptySet(), isRunning = false)
                        } else {
                            current
                        }
                    }
                }
            }
        }
    }

    /**
     * Dispatch a refresh for [category] (or all categories when null). Mirrors
     * `LibraryController.updateCategory` at LibraryController.kt:1779-1818:
     *
     * - Real categories (`id >= 0`, `isDynamic == false`): forwarded as-is. The worker
     *   resolves the bucket from the DB via `manga.category == id`.
     * - Dynamic categories (`isDynamic == true`, synthetic negative id): the worker can't
     *   resolve a synthetic id back to a DB filter, so we look up the bucket in the current
     *   loaded library and hand the worker the manga list directly via `mangaToUse`.
     *
     * Optimistic in-queue update: as soon as we dispatch, we add [Category.id] to
     * `inQueueCategoryIds` so the per-header spinner appears without waiting for the
     * `WorkManager.isRunningFlow` → RUNNING tick + reactive snap re-derivation. Without this,
     * the spinner can lag a few hundred milliseconds after the tap. The reactive derivation
     * later replaces the set with the worker-populated `LibraryUpdateJob.categoryIds`, which
     * (with the C10 legacy fix) correctly includes synthetic ids.
     */
    fun refresh(category: Category?): Boolean {
        val mangaToUse = if (category?.isDynamic == true) {
            val loaded = state.value as? LibraryTabState.Loaded ?: return false
            // Look up the bucket by id rather than reference: the Category passed in may be a
            // clone (per-header sort dispatch already does this elsewhere) and we want the
            // up-to-date items list from the current render.
            loaded.library.entries
                .firstOrNull { it.key.id == category.id }
                ?.value
                ?.map { it.libraryManga }
                ?: return false
        } else {
            null
        }
        val started = libraryUpdater.startNow(category, mangaToUse = mangaToUse)
        // Optimistic spinner — applies to all category types. Race-loss case is bounded to a
        // brief visual flicker (see KDoc).
        category?.id?.let { catId ->
            mutableState.update { current ->
                if (current is LibraryTabState.Loaded) {
                    current.copy(
                        inQueueCategoryIds = current.inQueueCategoryIds + catId,
                        isRunning = true,
                    )
                } else {
                    current
                }
            }
        }
        return started
    }

    fun stopRefresh() = libraryUpdater.stop()

    fun isCategoryInQueue(categoryId: Int?): Boolean = libraryUpdater.isCategoryInQueue(categoryId)

    fun isRunning(): Boolean = libraryUpdater.isRunning()

    /**
     * Next unread chapter for [manga], or null if none. Used by the Compose library's
     * continue-reading shortcut so the composable no longer reaches for [GetChapter] /
     * [ChapterSort] itself. Mirrors `LibraryController.openManga`'s continue-reading path.
     */
    suspend fun nextUnreadChapter(manga: Manga): Chapter? {
        val chapters = getChapter.awaitAll(manga)
        return ChapterSort(manga).getNextUnreadChapter(chapters, false)
    }

    /**
     * Apply a sort change to a category. Tapping the currently-selected mode flips direction;
     * tapping a different mode uses the mode's natural default (ascending for most modes,
     * descending for `hasInvertedSort` ones like LastRead and TotalChapters). Random always
     * re-rolls the seed so subsequent renders shuffle anew (mirrors legacy
     * `LibraryHeaderHolder.kt:349`).
     *
     * Persistence is routed per category type:
     *  - All-category sentinel (`id == -1`) and dynamic categories write library-wide prefs
     *    so every dynamic group stays in sync (legacy `LibraryPresenter.sortCategory` at
     *    `LibraryPresenter.kt:1560-1566`).
     *  - Default category (`id == 0`) writes `defaultMangaOrder` (legacy at `:1569`).
     *  - Regular categories persist via [updateCategories] (legacy at `:1571-1577`).
     */
    fun setSort(category: Category, mode: LibrarySort) {
        // Re-resolve the category from current state so the direction-toggle reads the freshest
        // sortingMode / isAscending values. The passed-in category may be a snapshot from the
        // composition that opened the sort sheet, while state has since updated from a previous
        // setSort write (race between async DB / pref propagation and the next user tap).
        val catId = category.id ?: return
        val current = (state.value as? LibraryTabState.Loaded)?.library?.keys
            ?.firstOrNull { it.id == catId }
            ?: category

        if (mode == LibrarySort.Random) {
            libraryPreferences.randomSortSeed().set(Random.nextInt())
        }
        val ascending = if (mode == current.sortingMode() && mode.isDirectional) {
            !current.isAscending()
        } else {
            !mode.hasInvertedSort
        }
        val newSortChar = if (ascending) mode.categoryValue else mode.categoryValueDescending

        // Persist (DB write or pref write). DB writes are fire-and-forget; reactivity catches
        // up via getCategories.subscribe and pref writes via sortPrefsFlow.
        when {
            current.id == -1 || current.isDynamic -> {
                preferences.librarySortingMode().set(mode.mainValue)
                preferences.librarySortingAscending().set(ascending)
            }
            current.id == 0 -> {
                preferences.defaultMangaOrder().set(newSortChar.toString())
            }
            else -> {
                screenModelScope.launchIO {
                    updateCategories.awaitOne(
                        CategoryUpdate(
                            id = catId.toLong(),
                            mangaOrder = newSortChar.toString(),
                        ),
                    )
                }
            }
        }

        // Mirror legacy `requestSortUpdate` (LibraryPresenter.kt:1579): build a fresh Category
        // with the new sort and replace it in the library map so the UI updates the moment the
        // user taps a mode, rather than waiting for the reactive DB / pref round-trip. The
        // eventual reactive emission carries the same data; StateFlow elides it via equals.
        //
        // Cloning (instead of mutating `current.mangaSort` in place) is what makes this work
        // visibly on the LazyColumn header slots. With in-place mutation, the captured Category
        // reference stays the same and Compose's slot reuse can render the previous tap's
        // sortMode label even after a key change. Replacing the Category with a new instance
        // forces every captured reference to be fresh, so the header recomposes with the new
        // sortingMode / isAscending. Random's seed write already triggers a full pipeline re-run
        // via sortPrefsFlow, so the optimistic step would be redundant (and items shuffle on
        // every Random tap anyway).
        if (mode == LibrarySort.Random) return
        val loaded = state.value as? LibraryTabState.Loaded ?: return
        val clonedCategory = CategoryImpl().also {
            it.id = current.id
            it.name = current.name
            it.order = current.order
            it.flags = current.flags
            it.mangaOrder = current.mangaOrder
            it.mangaSort = newSortChar
            it.isAlone = current.isAlone
            it.isHidden = current.isHidden
            it.isDynamic = current.isDynamic
            it.sourceId = current.sourceId
            it.langId = current.langId
            it.isSystem = current.isSystem
        }
        val replaceKey: (Map.Entry<Category, List<LibraryItem.Manga>>) -> Category = { (cat, _) ->
            if (cat.id == catId) clonedCategory else cat
        }
        val defaultMode = LibrarySort.valueOf(preferences.librarySortingMode().get()) ?: LibrarySort.Title
        val defaultAscending = preferences.librarySortingAscending().get()
        val randomSeed = libraryPreferences.randomSortSeed().get().toLong()
        val removeArticles = preferences.removeArticles().get()
        val resorted = MangaLibrarySort.sort(
            library = loaded.library.mapKeys(replaceKey),
            libraryDefaultMode = defaultMode,
            libraryDefaultAscending = defaultAscending,
            randomSeed = randomSeed,
            removeArticles = removeArticles,
        )
        // The rendered map (filteredLibrary, what the composable draws) re-sorts the same way.
        // Sort changes order, never filter membership, so no suspend filter re-run is needed.
        val resortedFiltered = MangaLibrarySort.sort(
            library = loaded.filteredLibrary.mapKeys(replaceKey),
            libraryDefaultMode = defaultMode,
            libraryDefaultAscending = defaultAscending,
            randomSeed = randomSeed,
            removeArticles = removeArticles,
        )
        // Bump sortEpoch so StateFlow.update always emits, even if the new library map compares
        // equal to the old (CategoryImpl.equals is by name; a cloned Category reads as equal to
        // the original, and a same-order sorted list reads as equal to the previous list). The
        // epoch guarantees uniqueness so the optimistic sort always reaches the UI.
        mutableState.update { c ->
            if (c is LibraryTabState.Loaded) c.copy(
                library = resorted,
                filteredLibrary = resortedFiltered,
                sortEpoch = c.sortEpoch + 1,
            ) else c
        }
    }

    fun toggleSelection(mangaId: Long) {
        mutableState.update { current ->
            if (current is LibraryTabState.Loaded) {
                val next = if (mangaId in current.selection) {
                    current.selection - mangaId
                } else {
                    current.selection + mangaId
                }
                current.copy(selection = next)
            } else {
                current
            }
        }
    }

    fun clearSelection() {
        mutableState.update { current ->
            if (current is LibraryTabState.Loaded && current.selection.isNotEmpty()) {
                current.copy(selection = emptySet())
            } else {
                current
            }
        }
    }

    fun setSelection(mangaIds: Set<Long>) {
        mutableState.update { current ->
            if (current is LibraryTabState.Loaded) {
                current.copy(selection = mangaIds)
            } else {
                current
            }
        }
    }

    /**
     * Toggle every manga in a category in / out of the selection set, matching the legacy
     * select-all-in-category checkbox at refs/yokai/.../LibraryHeaderHolder.kt:354-356. If every
     * manga in the category is already selected, remove them all; otherwise add the missing
     * ones (covers the partial-already-selected case without first deselecting).
     */
    fun toggleCategorySelection(categoryId: Int) {
        mutableState.update { current ->
            if (current !is LibraryTabState.Loaded) return@update current
            val categoryEntry = current.library.entries.firstOrNull { it.key.id == categoryId }
                ?: return@update current
            val categoryIds = categoryEntry.value.mapNotNull { it.libraryManga.manga.id }.toSet()
            if (categoryIds.isEmpty()) return@update current
            val allSelected = categoryIds.all { it in current.selection }
            val newSelection = if (allSelected) {
                current.selection - categoryIds
            } else {
                current.selection + categoryIds
            }
            current.copy(selection = newSelection)
        }
    }

    /**
     * Resolve current selection ids to their backing [Manga] entries via state.library. No DB
     * call needed: every selected manga is, by definition, currently rendered in the grid.
     */
    fun selectedMangaList(): List<Manga> {
        val loaded = state.value as? LibraryTabState.Loaded ?: return emptyList()
        val ids = loaded.selection
        if (ids.isEmpty()) return emptyList()
        return loaded.library.values
            .asSequence()
            .flatten()
            .map { it.libraryManga.manga }
            .filter { it.id in ids }
            .toList()
    }

    /**
     * Expand the current selection to include every member of any merge group the selection
     * touches. Walks the rendered library, finds items whose id is in the selection, and
     * unions in each item's [LibraryItem.Manga.relatedMangaIds]. Items without related ids
     * (unmerged manga, dynamic-grouping items) contribute just their own id, so the helper is
     * a no-op for selections that don't include a merged-group leader.
     *
     * Mirrors legacy `LibraryController.kt:2150-2161` (merge), `:2226-2236` (delete),
     * `:2266-2284` (move) — all three legacy paths walk `currentLibraryItems` and expand via
     * `LibraryMangaItem.relatedMangaIds`. Default grouping stamps related ids on leaders via
     * [MangaLibraryGrouping.collapse]; dynamic groupings ([MangaLibraryDynamicGrouping.build])
     * intentionally don't stamp them because each manga renders standalone in a dynamic view
     * — selection in dynamic view therefore operates on just the manga the user sees, matching
     * legacy parity.
     */
    private fun expandSelectionWithMergedSiblings(): Set<Long> {
        val loaded = state.value as? LibraryTabState.Loaded ?: return emptySet()
        val selection = loaded.selection
        if (selection.isEmpty()) return emptySet()
        return loaded.library.values
            .asSequence()
            .flatten()
            .filter { it.libraryManga.manga.id in selection }
            .flatMap { item ->
                if (item.relatedMangaIds.isNotEmpty()) {
                    item.relatedMangaIds.toList()
                } else {
                    listOfNotNull(item.libraryManga.manga.id)
                }
            }
            .toSet()
    }

    /**
     * Like [selectedMangaList] but pulls in every member of any merge group the selection
     * touches. Used by delete + move-to-category so a merged-group leader doesn't get an
     * action applied to it in isolation, orphaning the siblings.
     *
     * Resolves [Manga] objects from `libraryMangaForResolve` (the full pre-collapse list)
     * rather than from `library.values` — merge-group siblings are dropped by
     * [MangaLibraryGrouping.collapse] and only their leaders survive in the rendered list,
     * so a filter against `library.values` would silently return only the leader and let
     * downstream delete / move operations skip every sibling. Mirrors legacy
     * `presenter.getLibraryMangaById(it)?.manga` lookups at `LibraryController.kt:2234`
     * (delete) / `:2277` (move).
     */
    fun selectedMangaListWithMergedSiblings(): List<Manga> {
        if (state.value !is LibraryTabState.Loaded) return emptyList()
        val expanded = expandSelectionWithMergedSiblings()
        if (expanded.isEmpty()) return emptyList()
        return libraryMangaForResolve
            .asSequence()
            .map { it.manga }
            .filter { it.id in expanded }
            .distinctBy { it.id }
            .toList()
    }

    fun shareSelection(): List<String> =
        MangaLibraryActions.share(selectedMangaList(), sourceManager)

    fun downloadUnreadSelection() = downloadSelection(DownloadAction.UNREAD)

    fun downloadSelection(action: DownloadAction) {
        val mangas = selectedMangaList()
        screenModelScope.launchIO {
            MangaLibraryActions.download(mangas, action, getChapter, downloadManager)
        }
    }

    /**
     * Apply read / unread to every chapter of the selection. Returns the pre-update snapshot
     * (Manga -> original chapters) so the caller can offer Undo via [undoMarkReadStatus] or
     * commit cleanup via [confirmMarkReadStatus].
     */
    suspend fun markReadStatus(markRead: Boolean): Map<Manga, List<Chapter>> =
        MangaLibraryActions.markReadStatus(
            mangas = selectedMangaList(),
            markRead = markRead,
            getChapter = getChapter,
            updateChapter = updateChapter,
        )

    fun undoMarkReadStatus(snapshot: Map<Manga, List<Chapter>>) {
        screenModelScope.launchIO {
            MangaLibraryActions.undoMarkReadStatus(snapshot, updateChapter)
        }
    }

    fun confirmMarkReadStatus(snapshot: Map<Manga, List<Chapter>>, markRead: Boolean) {
        MangaLibraryActions.confirmMarkReadStatus(
            snapshot = snapshot,
            markRead = markRead,
            removeAfterMarkedAsRead = preferences.removeAfterMarkedAsRead().get(),
            downloadManager = downloadManager,
            sourceManager = sourceManager,
        )
    }

    /**
     * Path 1 (full nuke): flip favorite=false immediately, return the captured selection so
     * the caller can offer Undo via [reAddToLibrary] or commit destructive cleanup via
     * [confirmDeletion]. State reload happens reactively via getLibraryManga.subscribe().
     */
    fun removeFromLibrary(): List<Manga> {
        // Expanded: a delete on a merged-group leader pulls every sibling along, matching
        // legacy `deleteMangasFromLibrary` at `LibraryController.kt:2210-2220`. The expanded
        // list also flows through the undo snackbar → confirmDeletion / reAddToLibrary so
        // every member gets undone or cleaned up uniformly.
        val mangas = selectedMangaListWithMergedSiblings()
        screenModelScope.launchIO {
            MangaLibraryActions.removeFromLibrary(mangas, updateManga)
        }
        return mangas
    }

    fun reAddToLibrary(mangas: List<Manga>) {
        screenModelScope.launchIO {
            MangaLibraryActions.reAddToLibrary(mangas, updateManga)
        }
    }

    /**
     * Destructive cleanup. coverCacheToo = true on the full-nuke path (cover removal + tracker
     * reconciliation invalidation + track delete + download delete); false on the
     * downloads-only path (skip cover, skip tracks, still wipes downloaded chapters).
     */
    fun confirmDeletion(mangas: List<Manga>, coverCacheToo: Boolean = true) {
        screenModelScope.launchIO {
            MangaLibraryActions.confirmDeletion(
                mangas = mangas,
                coverCacheToo = coverCacheToo,
                sourceManager = sourceManager,
                downloadManager = downloadManager,
                coverCache = coverCache,
                deleteTrack = deleteTrack,
                preferences = preferences,
            )
        }
    }

    fun mergeSelection() {
        // Capture the expanded id set SYNCHRONOUSLY before launching the IO coroutine. The
        // action-bar handler in LibraryScreen calls `mergeSelection()` followed immediately by
        // `clearSelection()`; if we read state.value.selection from inside the launchIO
        // (via expandSelectionWithMergedSiblings), clearSelection wins the race and the
        // expansion sees an empty set, silently dropping the merge. Pre-C11 the same capture
        // happened via the helper's `ids: Set<Long>` parameter; C11 removed the parameter and
        // accidentally moved the read inside the coroutine. Keeping the capture synchronous
        // here matches the pattern unmergeSelection / removeFromLibrary already use.
        //
        // Expansion also pulls in every member of any merge group the selection touches —
        // without this, merging [m1, m4] when m1 is already in group [m1, m2, m3] would drop
        // the old entry (collision pruning) and orphan m2 and m3. In dynamic groupings the
        // rendered items don't carry relatedMangaIds, so a merge dispatched from a dynamic
        // view operates on exactly the manga the user selected — same as legacy.
        val expandedIds = expandSelectionWithMergedSiblings().toList()
        if (expandedIds.size < 2) return
        screenModelScope.launchIO {
            val sorted = MangaLibraryActions.merge(expandedIds, preferences)
            if (preferences.syncTrackerLinksGrouped().get()) {
                MangaLibraryActions.reconcileGroupTrackers(
                    ids = sorted,
                    getTrack = getTrack,
                    insertTrack = insertTrack,
                    preferences = preferences,
                )
            }
        }
    }

    fun unmergeSelection() {
        val ids = (state.value as? LibraryTabState.Loaded)?.selection?.toList().orEmpty()
        if (ids.isEmpty()) return
        screenModelScope.launchIO {
            MangaLibraryActions.unmerge(ids, preferences)
        }
    }

    /**
     * Bundles the sort-related prefs into a single combine() input so the main pipeline's
     * `.combine(...)` chain stays shallow. Read by [MangaLibrarySort] inside collectLatest.
     *
     * Uses the typed 5-arg `combine` overload (the kotlinx-coroutines limit) so each input keeps
     * its own type; the vararg overload requires a reified common type and would collapse Int /
     * Boolean / String to their intersection (Comparable<*> & Serializable), producing a
     * compiler warning and unstable behaviour.
     */
    private fun sortPrefsFlow() = combine(
        preferences.librarySortingMode().changes(),
        preferences.librarySortingAscending().changes(),
        libraryPreferences.randomSortSeed().changes(),
        preferences.removeArticles().changes(),
        preferences.defaultMangaOrder().changes(),
    ) { modeInt, ascending, seed, removeArticles, defaultMangaOrder ->
        SortPrefs(
            mode = LibrarySort.valueOf(modeInt) ?: LibrarySort.Title,
            ascending = ascending,
            randomSeed = seed.toLong(),
            removeArticles = removeArticles,
            defaultMangaOrder = defaultMangaOrder,
        )
    }

    /**
     * Bundles the three dynamic-grouping prefs so the main combine chain stays shallow.
     */
    private fun groupingPrefsFlow() = combine(
        preferences.groupLibraryBy().changes(),
        preferences.collapsedDynamicCategories().changes(),
        preferences.collapsedDynamicAtBottom().changes(),
    ) { groupBy, collapsed, atBottom -> GroupingPrefs(groupBy, collapsed, atBottom) }

    private data class GroupingPrefs(
        val groupLibraryBy: Int,
        val collapsedDynamicCategories: Set<String>,
        val collapsedDynamicAtBottom: Boolean,
    ) {
        companion object {
            val DEFAULT = GroupingPrefs(
                groupLibraryBy = LibraryGroup.BY_DEFAULT,
                collapsedDynamicCategories = emptySet(),
                collapsedDynamicAtBottom = false,
            )
        }
    }

    /**
     * Bundles the search query + the eight filter prefs into one [FilterInputs] stream. Held
     * separate from the raw-library pipeline so a change here re-runs only the search + suspend
     * filter (gated downstream by `distinctUntilChanged`), not the grouping/sort build.
     * `FILTER_TRACKER` is a JVM static read at build time, piggybacking on the filterTracked
     * change like the legacy sheet does.
     */
    private fun filterInputsFlow() = combine(
        searchQueryFlow,
        preferences.filterUnread().changes(),
        preferences.filterDownloaded().changes(),
        preferences.filterCompleted().changes(),
        preferences.filterTracked().changes(),
    ) { query, unread, downloaded, completed, tracked ->
        FilterAcc(query, unread, downloaded, completed, tracked)
    }
        .combine(preferences.filterBookmarked().changes()) { acc, bookmarked -> acc.copy(bookmarked = bookmarked) }
        .combine(preferences.filterContentType().changes()) { acc, contentType -> acc.copy(contentType = contentType) }
        .combine(preferences.filterMangaType().changes()) { acc, mangaType -> acc.copy(mangaType = mangaType) }
        .combine(preferences.showAllCategories().changes()) { acc, showAll ->
            FilterInputs(
                query = acc.query,
                filterState = MangaLibraryFilter.MangaFilterState(
                    downloaded = acc.downloaded,
                    unread = acc.unread,
                    completed = acc.completed,
                    tracked = acc.tracked,
                    mangaType = acc.mangaType,
                    contentType = acc.contentType,
                    bookmarked = acc.bookmarked,
                    tracker = FilterBottomSheet.FILTER_TRACKER,
                ),
                showAllCategories = showAll,
            )
        }

    private data class FilterAcc(
        val query: String,
        val unread: Int,
        val downloaded: Int,
        val completed: Int,
        val tracked: Int,
        val bookmarked: Int = 0,
        val contentType: Int = 0,
        val mangaType: Int = 0,
    )

    private data class FilterInputs(
        val query: String,
        val filterState: MangaLibraryFilter.MangaFilterState,
        val showAllCategories: Boolean,
    )

    /** Raw-library pipeline output, before search + filter. */
    private data class RawData(
        val library: Map<Category, List<LibraryItem.Manga>>,
        val isRunning: Boolean,
        val inQueue: Set<Int>,
        val currentCategoryOrder: Int,
        val categorySortOrder: Int,
        val collapsedDynamicCategories: Set<String>,
        val collapsedDynamicAtBottom: Boolean,
        val sourceNames: Map<Long, String>,
        val seriesTypes: Map<Long, String>,
        val detectedTypes: Set<Int>,
        val loggedServiceNames: Map<Long, String>,
    )

    /** Raw data + the search/filter result for one emission. */
    private data class Rendered(
        val raw: RawData,
        val isAnyFilterActive: Boolean,
        val filteredLibrary: Map<Category, List<LibraryItem.Manga>>,
    )

    /**
     * Display / badge / layout / category preferences (Tier 2 phase 2B). Bundled into sub-groups
     * to stay within the typed 5-arg `combine` limit, then folded into [DisplayPrefs]. Combined
     * downstream of the search/filter stage so a cosmetic change updates the render without
     * re-running the suspend filter.
     */
    private fun displayPrefsFlow() = combine(
        layoutPrefsFlow(),
        badgePrefsFlow(),
        categoryPrefsFlow(),
        hopperPrefsFlow(),
        miscPrefsFlow(),
    ) { layout, badge, category, hopper, misc ->
        DisplayPrefs(layout, badge, category, hopper, misc)
    }

    private fun layoutPrefsFlow() = combine(
        preferences.libraryLayout().changes(),
        uiPreferences.uniformGrid().changes(),
        preferences.useStaggeredGrid().changes(),
        preferences.gridSize().changes(),
        uiPreferences.outlineOnCovers().changes(),
    ) { layout, uniform, staggered, gridSize, outline ->
        LayoutPrefs(layout, uniform, staggered, gridSize, outline)
    }

    private fun badgePrefsFlow() = combine(
        preferences.downloadBadge().changes(),
        preferences.languageBadge().changes(),
        preferences.unreadBadgeType().changes(),
        preferences.hideStartReadingButton().changes(),
    ) { download, language, unread, hideStart ->
        BadgePrefs(download, language, unread, hideStart)
    }

    private fun categoryPrefsFlow() = combine(
        preferences.groupLibraryBy().changes(),
        preferences.collapsedCategories().changes(),
        preferences.showAllCategories().changes(),
        preferences.showEmptyCategoriesWhileFiltering().changes(),
        preferences.lastUsedCategory().changes(),
    ) { groupBy, collapsed, showAll, showEmpty, lastUsed ->
        CategoryPrefs(groupBy, collapsed, showAll, showEmpty, lastUsed)
    }

    private fun hopperPrefsFlow() = combine(
        preferences.hideHopper().changes(),
        preferences.autohideHopper().changes(),
        preferences.hopperGravity().changes(),
        preferences.hopperLongPressAction().changes(),
    ) { hide, autohide, gravity, longPress ->
        HopperPrefs(hide, autohide, gravity, longPress)
    }

    private fun miscPrefsFlow() = combine(
        preferences.showCategoryInTitle().changes(),
        preferences.categoryNumberOfItems().changes(),
        preferences.mangaManualMerges().changes(),
    ) { inTitle, itemCounts, merges ->
        MiscPrefs(inTitle, itemCounts, merges)
    }

    private data class LayoutPrefs(
        val libraryLayout: Int,
        val uniformGrid: Boolean,
        val useStaggeredGrid: Boolean,
        val gridSize: Float,
        val outlineOnCovers: Boolean,
    )

    private data class BadgePrefs(
        val showDownloadBadge: Boolean,
        val showLanguageBadge: Boolean,
        val unreadBadgeType: Int,
        val hideStartReadingButton: Boolean,
    )

    private data class CategoryPrefs(
        val groupLibraryBy: Int,
        val collapsedCategories: Set<String>,
        val showAllCategories: Boolean,
        val showEmptyCategoriesWhileFiltering: Boolean,
        val lastUsedCategoryOrder: Int,
    )

    private data class HopperPrefs(
        val hideHopper: Boolean,
        val autohideHopper: Boolean,
        val hopperGravity: Int,
        val hopperLongPressAction: Int,
    )

    private data class MiscPrefs(
        val showCategoryInTitle: Boolean,
        val showCategoryItemCounts: Boolean,
        val manualMerges: Set<String>,
    )

    private data class DisplayPrefs(
        val layout: LayoutPrefs,
        val badge: BadgePrefs,
        val category: CategoryPrefs,
        val hopper: HopperPrefs,
        val misc: MiscPrefs,
    )

    /**
     * Resolves a manga's source language. Mirrors legacy [eu.kanade.tachiyomi.ui.library.LibraryPresenter.getLanguage]
     * at `LibraryPresenter.kt:666-672`.
     */
    private fun languageOf(manga: Manga): String? =
        if (manga.isLocal()) LocalSource.getMangaLang(manga) else sourceManager.get(manga.source)?.lang

    /**
     * Resolves a language code into a localized display name (e.g. "en" → "English"). Mirrors
     * the inline expression at legacy `LibraryPresenter.kt:1226-1230`.
     */
    private fun languageDisplay(langCode: String): String {
        val locale = Locale.forLanguageTag(langCode)
        return locale.getDisplayName(locale).replaceFirstChar { it.uppercase(locale) }
    }

    /**
     * Maps a tracker status name to a sort-prefix so BY_TRACK_STATUS orders by intent (Reading
     * first, Dropped last) rather than alphabetically. Mirrors legacy
     * [eu.kanade.tachiyomi.ui.library.LibraryPresenter.mapTrackingOrder] at
     * `LibraryPresenter.kt:1302-1314`.
     */
    private fun mapTrackingOrder(status: String): String = with(preferences.context) {
        when (status) {
            getString(MR.strings.reading), getString(MR.strings.currently_reading) -> "1"
            getString(MR.strings.rereading) -> "2"
            getString(MR.strings.plan_to_read), getString(MR.strings.want_to_read) -> "3"
            getString(MR.strings.on_hold), getString(MR.strings.paused) -> "4"
            getString(MR.strings.completed) -> "5"
            getString(MR.strings.dropped) -> "6"
            else -> "7"
        }
    }

    /**
     * Toggle the collapse state of a dynamic category. Writes to `collapsedDynamicCategories`
     * keyed by [Category.dynamicHeaderKey] (mirrors legacy
     * `LibraryPresenter.kt:1653-1677`). Default-grouping categories use a separate
     * `collapsedCategories` pref keyed by integer category id and are handled by the existing
     * `onToggleCategoryCollapse` callback in `LibraryScreen`.
     */
    fun toggleDynamicCategoryCollapse(category: Category) {
        if (!category.isDynamic) return
        val key = category.dynamicHeaderKey()
        val current = preferences.collapsedDynamicCategories().get().toMutableSet()
        if (!current.add(key)) current.remove(key)
        preferences.collapsedDynamicCategories().set(current)
    }

    /**
     * Toggle a default (non-dynamic) category's collapsed state, keyed by its integer id as a
     * string in `collapsedCategories`. Mirrors the composable's previous inline pref write.
     */
    fun toggleDefaultCategoryCollapse(categoryId: String) {
        val current = preferences.collapsedCategories().get().toMutableSet()
        if (!current.add(categoryId)) current.remove(categoryId)
        preferences.collapsedCategories().set(current)
    }

    /**
     * Expand-or-collapse-all toggle for default categories: collapse every id when none are
     * collapsed, otherwise clear the set (hopper long-press index 1).
     */
    fun expandOrCollapseAllDefaultCategories(allIds: Set<String>) {
        val current = preferences.collapsedCategories().get()
        preferences.collapsedCategories().set(if (current.isEmpty()) allIds else emptySet())
    }

    fun setHopperGravity(value: Int) = preferences.hopperGravity().set(value)

    fun setGroupLibraryBy(value: Int) = preferences.groupLibraryBy().set(value)

    /** Persist the active category order (only for real categories; callers gate `order >= 0`). */
    fun setLastUsedCategory(order: Int) = preferences.lastUsedCategory().set(order)

    fun skipPreMigration(): Boolean = preferences.skipPreMigration().get()

    private data class SortPrefs(
        val mode: LibrarySort,
        val ascending: Boolean,
        val randomSeed: Long,
        val removeArticles: Boolean,
        val defaultMangaOrder: String,
    ) {
        companion object {
            // Placeholder used in the first combine() before sortPrefsFlow() emits. The chained
            // .combine(sortPrefsFlow()) below overwrites this before any downstream emission, so
            // these values never reach collectLatest.
            val DEFAULT = SortPrefs(
                mode = LibrarySort.Title,
                ascending = true,
                randomSeed = 0L,
                removeArticles = false,
                defaultMangaOrder = "",
            )
        }
    }

    private data class Snapshot(
        val categories: List<Category>,
        val libraryManga: List<LibraryManga>,
        val isRunning: Boolean,
        val currentCategoryOrder: Int,
        val manualMerges: Set<String>,
        val manualUnmerges: Set<String>,
        val autoMergeSameTitle: Boolean,
        val sortPrefs: SortPrefs,
        /** Reikai-fork `preferences.categorySortOrder`: 0 manual, 1 A→Z, 2 Z→A. */
        val categorySortOrder: Int,
        /** groupLibraryBy mode + collapsed-dynamic state, bundled for chain shallowness. */
        val groupingPrefs: GroupingPrefs,
    )
}
