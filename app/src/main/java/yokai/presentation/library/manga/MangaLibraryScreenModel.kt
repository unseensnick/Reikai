package yokai.presentation.library.manga

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.injectLazy
import yokai.domain.category.interactor.GetCategories
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.chapter.interactor.UpdateChapter
import yokai.domain.manga.interactor.GetLibraryManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.track.interactor.DeleteTrack
import yokai.domain.track.interactor.GetTrack
import yokai.domain.track.interactor.InsertTrack
import yokai.presentation.library.LibraryTabState
import yokai.presentation.library.manga.actions.MangaLibraryActions
import eu.kanade.tachiyomi.data.cache.CoverCache

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

    init {
        screenModelScope.launchIO {
            combine(
                getCategories.subscribe(),
                getLibraryManga.subscribe(),
                downloadCache.changes,
                libraryUpdater.isRunningFlow(),
                preferences.lastUsedCategory().changes(),
            ) { categories, libraryManga, _, isRunning, currentCategoryOrder ->
                Snapshot(categories, libraryManga, isRunning, currentCategoryOrder)
            }
                .collectLatest { snap ->
                    // Recreate per emission so a locale change between subscriptions picks up the
                    // re-translated "Default" string, matching legacy LibraryPresenter behavior.
                    val defaultCategory = Category.createDefault(preferences.context).apply {
                        order = -1
                    }
                    val library = MangaLibrarySectioner.section(
                        libraryManga = snap.libraryManga,
                        userCategories = snap.categories,
                        defaultCategory = defaultCategory,
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
                        // library update mid-action doesn't drop the user's selection set.
                        val carriedSelection =
                            (current as? LibraryTabState.Loaded)?.selection ?: emptySet()
                        LibraryTabState.Loaded(
                            library = library,
                            totalItemCount = library.values.sumOf { it.size },
                            isRunning = snap.isRunning,
                            inQueueCategoryIds = inQueue,
                            currentCategoryOrder = snap.currentCategoryOrder,
                            selection = carriedSelection,
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
        val mangas = selectedMangaList()
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
            val sorted = MangaLibraryActions.merge(ids, preferences)
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

    private data class Snapshot(
        val categories: List<Category>,
        val libraryManga: List<LibraryManga>,
        val isRunning: Boolean,
        val currentCategoryOrder: Int,
    )
}
