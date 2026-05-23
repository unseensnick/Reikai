package yokai.presentation.library.manga

import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.database.models.Category
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
 * Phase 1 screen model. Collects library manga and user categories, runs them through
 * [MangaLibrarySectioner], and emits [LibraryTabState] for the Compose library to render.
 *
 * The download-cache flow is folded in now (even though Phase 1 doesn't yet render download
 * badges) so that later phases can extend the combine block without restructuring it. Pattern
 * mirrors [yokai.presentation.extension.repo.ExtensionRepoScreenModel]: `injectLazy()` for
 * dependencies, `screenModelScope.launchIO { ... collectLatest { mutableState.update { ... } } }`
 * for flow collection.
 */
class MangaLibraryScreenModel :
    StateScreenModel<LibraryTabState<LibraryItem.Manga>>(LibraryTabState.Loading) {

    private val preferences: PreferencesHelper by injectLazy()
    private val getCategories: GetCategories by injectLazy()
    private val getLibraryManga: GetLibraryManga by injectLazy()
    private val downloadCache: DownloadCache by injectLazy()

    init {
        screenModelScope.launchIO {
            combine(
                getCategories.subscribe(),
                getLibraryManga.subscribe(),
                downloadCache.changes,
            ) { categories, libraryManga, _ -> categories to libraryManga }
                .collectLatest { (categories, libraryManga) ->
                    // Recreate per emission so a locale change between subscriptions picks up the
                    // re-translated "Default" string, matching legacy LibraryPresenter behavior.
                    val defaultCategory = Category.createDefault(preferences.context).apply {
                        order = -1
                    }
                    val library = MangaLibrarySectioner.section(
                        libraryManga = libraryManga,
                        userCategories = categories,
                        defaultCategory = defaultCategory,
                    )
                    mutableState.update {
                        LibraryTabState.Loaded(
                            library = library,
                            totalItemCount = library.values.sumOf { it.size },
                        )
                    }
                }
        }
    }
}
