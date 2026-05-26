package yokai.presentation.library.novels

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.database.models.LibraryNovel
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.data.database.models.NovelCategoryImpl
import eu.kanade.tachiyomi.util.system.launchIO
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import yokai.domain.novel.NovelPreferences
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.interactor.GetNovelCategories
import yokai.presentation.library.novels.state.NovelLibraryTabState

/**
 * Novel-side parallel of [yokai.presentation.library.manga.MangaLibraryScreenModel]. Collects
 * categories, library novels, the [NovelLibraryUpdater] running flag, and the
 * `lastUsedNovelCategory` preference; folds them through [NovelLibrarySectioner] (C17) and
 * surfaces [NovelLibraryTabState] to the future Novels tab.
 *
 * **C24 skeleton.** Only the BY_DEFAULT pipeline is wired (sectioner → emit Loaded).
 * C25 layers on the binary-combine chain for merge / sort / grouping prefs, the dynamic-
 * grouping branch, and the collapse → sort pipeline. C26-C32 add selection / actions.
 *
 * Uses [KoinComponent] + `by inject()` (not `Injekt.get()`): all novel-side dependencies
 * (NovelPreferences, NovelRepository, GetNovelCategories, NovelLibraryUpdater) are
 * Koin-only registrations. Same lesson as C14d.
 *
 * The combine chain has **4 args** (not the manga side's 5) because Decision #4 stubs novel
 * downloads — there is no `downloadCache.changes` to invalidate render state on.
 */
class NovelLibraryScreenModel :
    StateScreenModel<NovelLibraryTabState>(NovelLibraryTabState.Loading), KoinComponent {

    private val novelPreferences: NovelPreferences by inject()
    private val getNovelCategories: GetNovelCategories by inject()
    private val novelRepository: NovelRepository by inject()
    private val novelLibraryUpdater: NovelLibraryUpdater by inject()

    init {
        screenModelScope.launchIO {
            combine(
                getNovelCategories.subscribe(),
                novelRepository.getLibraryNovelAsFlow(),
                novelLibraryUpdater.isRunningFlow(),
                novelPreferences.lastUsedNovelCategory().changes(),
            ) { categories, libraryNovel, isRunning, currentCategoryOrder ->
                Snapshot(
                    categories = categories,
                    libraryNovel = libraryNovel,
                    isRunning = isRunning,
                    currentCategoryOrder = currentCategoryOrder,
                )
            }
                .collectLatest { snap ->
                    // C24 BY_DEFAULT-only pipeline: sectioner alone, no grouping / sort yet.
                    // C25 replaces this block with the full binary-combine chain + branch +
                    // collapse → sort pipeline AND swaps the hard-coded "Default" string below
                    // for a Context-aware `NovelCategory.createDefault(context)` call so the
                    // localized string is honoured.
                    val defaultCategory = defaultCategoryFallback()
                    val library = NovelLibrarySectioner.section(
                        libraryNovel = snap.libraryNovel,
                        userCategories = snap.categories,
                        defaultCategory = defaultCategory,
                        categorySortOrder = 0,
                    )
                    mutableState.update { current ->
                        val existing = current as? NovelLibraryTabState.Loaded
                        NovelLibraryTabState.Loaded(
                            library = library,
                            totalItemCount = snap.libraryNovel.distinctBy { it.novel.id }.size,
                            isRunning = snap.isRunning,
                            inQueueCategoryIds = existing?.inQueueCategoryIds.orEmpty(),
                            currentCategoryOrder = snap.currentCategoryOrder,
                            selection = existing?.selection.orEmpty(),
                            sortEpoch = existing?.sortEpoch ?: 0,
                            categorySortOrder = existing?.categorySortOrder ?: 0,
                            collapsedDynamicCategories = existing?.collapsedDynamicCategories.orEmpty(),
                            collapsedDynamicAtBottom = existing?.collapsedDynamicAtBottom ?: false,
                            libraryNovelForResolve = snap.libraryNovel,
                        )
                    }
                }
        }
    }

    /**
     * Skeleton fallback for the Default category. C25 replaces with a Context-aware
     * `NovelCategory.createDefault(context)` so the localized "Default" string is honoured.
     */
    private fun defaultCategoryFallback(): NovelCategory = NovelCategoryImpl().apply {
        id = 0
        name = "Default"
        isSystem = true
    }

    private data class Snapshot(
        val categories: List<NovelCategory>,
        val libraryNovel: List<LibraryNovel>,
        val isRunning: Boolean,
        val currentCategoryOrder: Int,
    )
}
