package yokai.presentation.library.manga

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.CategoryImpl
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.library.LibrarySort
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
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
import yokai.presentation.library.LibraryTabState
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
class MangaLibraryScreenModel :
    StateScreenModel<LibraryTabState<LibraryItem.Manga>>(LibraryTabState.Loading) {

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

    init {
        screenModelScope.launchIO {
            combine(
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
                .collectLatest { snap ->
                    // Recreate per emission so a locale change between subscriptions picks up the
                    // re-translated "Default" string, matching legacy LibraryPresenter behavior.
                    // The default category's sort lives in `preferences.defaultMangaOrder` rather
                    // than the categories table; legacy reads it at LibraryPresenter.kt:1320-1322.
                    val defaultCategory = Category.createDefault(preferences.context).apply {
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
                    )
                    // Collapse merged-manga groups (manual merge + same-title auto-merge) into
                    // a single rendered entry per group, stamped with relatedMangaIds. Mirrors
                    // legacy LibraryPresenter.applySourceGrouping at LibraryPresenter.kt:943-997.
                    val grouped = MangaLibraryGrouping.collapse(
                        library = sectioned,
                        manualMerges = snap.manualMerges,
                        manualUnmerges = snap.manualUnmerges,
                        autoMergeSameTitle = snap.autoMergeSameTitle,
                    )
                    // Per-category sort (9 modes), with library-wide default as the fallback for
                    // categories whose mangaSort is unset. Pipeline order matches legacy:
                    // group, filter (Compose-side at render), sort.
                    val library = MangaLibrarySort.sort(
                        library = grouped,
                        libraryDefaultMode = snap.sortPrefs.mode,
                        libraryDefaultAscending = snap.sortPrefs.ascending,
                        randomSeed = snap.sortPrefs.randomSeed,
                        removeArticles = snap.sortPrefs.removeArticles,
                    )
                    val inQueue = if (snap.isRunning) {
                        library.keys.mapNotNullTo(HashSet()) { cat ->
                            cat.id?.takeIf { libraryUpdater.isCategoryInQueue(it) }
                        }
                    } else {
                        emptySet()
                    }
                    mutableState.update { current ->
                        // Preserve selection across reload emissions so a download-cache tick or
                        // library update mid-action doesn't drop the user's selection set. Also
                        // carry sortEpoch forward unchanged; combine-driven emissions never bump
                        // it (only optimistic sort writes do, see setSort).
                        val loaded = current as? LibraryTabState.Loaded
                        val carriedSelection = loaded?.selection ?: emptySet()
                        val carriedSortEpoch = loaded?.sortEpoch ?: 0
                        LibraryTabState.Loaded(
                            library = library,
                            totalItemCount = library.values.sumOf { it.size },
                            isRunning = snap.isRunning,
                            inQueueCategoryIds = inQueue,
                            currentCategoryOrder = snap.currentCategoryOrder,
                            selection = carriedSelection,
                            sortEpoch = carriedSortEpoch,
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

    fun refresh(category: Category?): Boolean = libraryUpdater.startNow(category)

    fun stopRefresh() = libraryUpdater.stop()

    fun isCategoryInQueue(categoryId: Int?): Boolean = libraryUpdater.isCategoryInQueue(categoryId)

    fun isRunning(): Boolean = libraryUpdater.isRunning()

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
        val replacedLibrary = loaded.library.mapKeys { (cat, _) ->
            if (cat.id == catId) clonedCategory else cat
        }
        val resorted = MangaLibrarySort.sort(
            library = replacedLibrary,
            libraryDefaultMode = LibrarySort.valueOf(preferences.librarySortingMode().get())
                ?: LibrarySort.Title,
            libraryDefaultAscending = preferences.librarySortingAscending().get(),
            randomSeed = libraryPreferences.randomSortSeed().get().toLong(),
            removeArticles = preferences.removeArticles().get(),
        )
        // Bump sortEpoch so StateFlow.update always emits, even if the new library map compares
        // equal to the old (CategoryImpl.equals is by name; a cloned Category reads as equal to
        // the original, and a same-order sorted list reads as equal to the previous list). The
        // epoch guarantees uniqueness so the optimistic sort always reaches the UI.
        mutableState.update { c ->
            if (c is LibraryTabState.Loaded) c.copy(
                library = resorted,
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
     * Expand a set of selected manga ids to include every member of any manual-merge group
     * that contains at least one selected manga. Reads `preferences.mangaManualMerges()`
     * directly; the Phase 6 plan replaces this with a read from `LibraryItem.Manga.relatedMangaIds`
     * once that field lands on the data model.
     */
    private fun expandSelectionWithMergedSiblings(ids: Set<Long>): Set<Long> {
        if (ids.isEmpty()) return ids
        val merges = preferences.mangaManualMerges().get()
        if (merges.isEmpty()) return ids
        return merges
            .asSequence()
            .map { entry -> entry.split(",").mapNotNull { it.trim().toLongOrNull() } }
            .filter { members -> members.any { it in ids } }
            .flatten()
            .toSet() + ids
    }

    /**
     * Like [selectedMangaList] but pulls in every member of any merge group the selection
     * touches. Used by delete + move-to-category + merge so a merged-group leader doesn't get
     * an action applied to it in isolation, orphaning the siblings. Mirrors legacy expansion
     * via `LibraryMangaItem.relatedMangaIds` at `LibraryController.kt:2120-2129` (merge),
     * `:2210-2220` (delete), `:2253-2262` (move).
     */
    fun selectedMangaListWithMergedSiblings(): List<Manga> {
        val loaded = state.value as? LibraryTabState.Loaded ?: return emptyList()
        val expanded = expandSelectionWithMergedSiblings(loaded.selection)
        if (expanded.isEmpty()) return emptyList()
        return loaded.library.values
            .asSequence()
            .flatten()
            .map { it.libraryManga.manga }
            .filter { it.id in expanded }
            .toList()
    }

    fun shareSelection(): List<String> =
        MangaLibraryActions.share(selectedMangaList(), sourceManager)

    fun downloadUnreadSelection() {
        val mangas = selectedMangaList()
        screenModelScope.launchIO {
            MangaLibraryActions.downloadUnread(mangas, getChapter, downloadManager)
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
        val ids = (state.value as? LibraryTabState.Loaded)?.selection?.toList().orEmpty()
        if (ids.size < 2) return
        screenModelScope.launchIO {
            // Expand to include every member of any merge group the selection touches; without
            // this, merging [m1, m4] when m1 is already in group [m1, m2, m3] would drop the
            // old entry (collision pruning) and orphan m2 and m3.
            val expandedIds = expandSelectionWithMergedSiblings(ids.toSet())
            val sorted = MangaLibraryActions.merge(expandedIds.toList(), preferences)
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
    )
}
