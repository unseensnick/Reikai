package yokai.presentation.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.presentation.library.manga.MangaLibraryScreenModel
import yokai.presentation.library.manga.MangaLibrarySearch

/**
 * Phase 1 single-tab manga library host. Phase 8 expands this into a tabbed shell with manga
 * and novel tabs sharing a common `LibraryTabContent` composable.
 *
 * Column count is fixed at adaptive 128.dp for Phase 1. `preferences.gridSize()` honoring lands
 * with the display-settings UI in Phase 3. Phase 2 adds an icon-toggled search bar wired to
 * [MangaLibrarySearch] for live filtering.
 */
class LibraryScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { MangaLibraryScreenModel() }
        val state by screenModel.state.collectAsState()
        val preferences: PreferencesHelper = Injekt.get()
        val sourceManager: SourceManager = Injekt.get()
        val libraryLayout = preferences.libraryLayout().get()
        val showCategoryInTitle = preferences.showCategoryInTitle().get()
        val hideHopper = preferences.hideHopper().get()
        val autohideHopper = preferences.autohideHopper().get()
        val hopperGravity = preferences.hopperGravity().get()

        var searchActive by rememberSaveable { mutableStateOf(false) }
        var searchQuery by rememberSaveable { mutableStateOf("") }

        val library = when (val s = state) {
            is LibraryTabState.Loading -> emptyMap()
            is LibraryTabState.Loaded -> s.library
        }

        // Precompute source names so MangaLibrarySearch stays pure and unit-testable. Recomputed
        // only when the library map identity changes, not on every keystroke.
        val sourceNames = remember(library) {
            library.values
                .asSequence()
                .flatten()
                .map { it.libraryManga.manga.source }
                .distinct()
                .associateWith { sourceManager.getOrStub(it).name }
        }

        val filteredLibrary = remember(library, searchQuery, sourceNames) {
            MangaLibrarySearch.search(library, searchQuery, sourceNames)
        }

        LibraryContent(
            library = filteredLibrary,
            columns = 0,
            libraryLayout = libraryLayout,
            searchActive = searchActive,
            searchQuery = searchQuery,
            showCategoryInTitle = showCategoryInTitle,
            hideHopper = hideHopper,
            autohideHopper = autohideHopper,
            hopperGravity = hopperGravity,
            onSearchActiveChange = { searchActive = it },
            onSearchQueryChange = { searchQuery = it },
        )
    }
}
