package yokai.presentation.library.manga

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.download.DownloadCache
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.library.models.LibraryItem
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import uy.kohesive.injekt.injectLazy
import yokai.domain.category.interactor.GetCategories
import yokai.domain.manga.interactor.GetLibraryManga
import yokai.presentation.library.LibraryTabState

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

    private data class Snapshot(
        val categories: List<Category>,
        val libraryManga: List<LibraryManga>,
        val isRunning: Boolean,
        val currentCategoryOrder: Int,
    )
}
