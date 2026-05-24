package yokai.presentation.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.library.filter.FilterBottomSheet
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.track.interactor.GetTrack
import yokai.presentation.library.manga.MangaLibraryFilter
import yokai.presentation.library.manga.MangaLibraryFilter.MangaFilterState
import yokai.presentation.library.manga.MangaLibraryScreenModel
import yokai.presentation.library.manga.MangaLibrarySearch
import yokai.util.lang.getString

/**
 * Phase 1+ single-tab manga library host. Phase 8 expands this into a tabbed shell with manga
 * and novel tabs sharing a common `LibraryTabContent` composable.
 *
 * Reads filter preferences reactively (`changes().collectAsState`) and pipes the library through
 * `MangaLibrarySearch` then `MangaLibraryFilter` before handing off to `LibraryContent`. Owns
 * the Display options sheet and overflow menu state; both are pure UI concerns kept local.
 */
class LibraryScreen : Screen {

    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { MangaLibraryScreenModel() }
        val state by screenModel.state.collectAsState()
        val preferences: PreferencesHelper = remember { Injekt.get() }
        val sourceManager: SourceManager = remember { Injekt.get() }
        val trackManager: TrackManager = remember { Injekt.get() }
        val downloadManager: DownloadManager = remember { Injekt.get() }
        val getTrack: GetTrack = remember { Injekt.get() }

        val libraryLayout = preferences.libraryLayout().get()
        val showCategoryInTitle = preferences.showCategoryInTitle().get()
        val showCategoryItemCounts = preferences.categoryNumberOfItems().get()
        val hideHopper = preferences.hideHopper().get()
        val autohideHopper = preferences.autohideHopper().get()
        val hopperGravityPref = remember { preferences.hopperGravity() }
        val hopperGravity by hopperGravityPref.changes()
            .collectAsState(initial = hopperGravityPref.get())

        // Cell size derived from preferences.gridSize() (a Float). Smaller pref = denser grid;
        // legacy uses `1.5f.pow(value)` as the cell-scale multiplier and a 128.dp baseline,
        // which lines up with Phase 1's adaptive 128.dp default at pref=0.
        val gridSizePref by preferences.gridSize().collectAsState()
        val cellMinSizeDp = (128f * 1.5f.pow(gridSizePref)).roundToInt().coerceIn(72, 320)

        // Filter prefs collected reactively so chip taps in the sheet flow through to the grid
        // without dismiss-and-reopen.
        val filterUnread by preferences.filterUnread().collectAsState()
        val filterDownloaded by preferences.filterDownloaded().collectAsState()
        val filterCompleted by preferences.filterCompleted().collectAsState()
        val filterBookmarked by preferences.filterBookmarked().collectAsState()
        val filterContentType by preferences.filterContentType().collectAsState()
        val filterMangaType by preferences.filterMangaType().collectAsState()
        val filterTracked by preferences.filterTracked().collectAsState()
        // FILTER_TRACKER is a JVM static (not a Flow); read it as initial state. The sheet
        // updates this var directly, and recomposes here happen via other-pref changes when
        // tracker-only changes also bump filterTracked.
        val filterTracker = remember(filterTracked) { FilterBottomSheet.FILTER_TRACKER }

        var searchActive by rememberSaveable { mutableStateOf(false) }
        var searchQuery by rememberSaveable { mutableStateOf("") }

        var sheetOpen by rememberSaveable { mutableStateOf(false) }
        var sheetTab by rememberSaveable { mutableIntStateOf(0) }
        var overflowOpen by remember { mutableStateOf(false) }

        val library = when (val s = state) {
            is LibraryTabState.Loading -> emptyMap()
            is LibraryTabState.Loaded -> s.library
        }

        val sourceNames = remember(library) {
            library.values
                .asSequence()
                .flatten()
                .map { it.libraryManga.manga.source }
                .distinct()
                .associateWith { sourceManager.getOrStub(it).name }
        }

        val searchedLibrary = remember(library, searchQuery, sourceNames) {
            MangaLibrarySearch.search(library, searchQuery, sourceNames)
        }

        // The filter is suspend (track lookups) but we want a synchronous Compose result. Run
        // it on the IO dispatcher via a produceState-style coroutine and surface via state.
        val filterState = MangaFilterState(
            downloaded = filterDownloaded,
            unread = filterUnread,
            completed = filterCompleted,
            tracked = filterTracked,
            mangaType = filterMangaType,
            contentType = filterContentType,
            bookmarked = filterBookmarked,
            tracker = filterTracker,
        )
        val loggedServiceNames = remember(trackManager) {
            trackManager.services
                .filter { it.isLogged }
                .associate { it.id to preferences.context.getString(it.nameRes()) }
        }
        val filteredLibrary by androidx.compose.runtime.produceState(
            initialValue = searchedLibrary,
            key1 = searchedLibrary,
            key2 = filterState,
        ) {
            value = if (!filterState.isAnyActive) searchedLibrary
            else kotlinx.coroutines.withContext(Dispatchers.Default) {
                MangaLibraryFilter.filter(
                    library = searchedLibrary,
                    state = filterState,
                    sourceManager = sourceManager,
                    loggedServiceNames = loggedServiceNames,
                    getDownloadCount = { manga -> downloadManager.getDownloadCount(manga) },
                    getTracks = { mangaId -> getTrack.awaitAllByMangaId(mangaId) },
                )
            }
        }

        val detectedMangaTypes = remember(library) {
            MangaLibraryFilter.detectMangaTypes(library, sourceManager)
        }
        val loggedTrackerNames = remember(loggedServiceNames) {
            loggedServiceNames.values.toList()
        }

        val allCategories = remember(library) { library.keys.toList() }
        val categoryItemCounts = remember(library) {
            library.entries.associate { (cat, items) -> (cat.id ?: 0) to items.size }
        }

        LibraryContent(
            library = filteredLibrary,
            allCategories = allCategories,
            categoryItemCounts = categoryItemCounts,
            showCategoryItemCounts = showCategoryItemCounts,
            cellMinSizeDp = cellMinSizeDp,
            libraryLayout = libraryLayout,
            searchActive = searchActive,
            searchQuery = searchQuery,
            showCategoryInTitle = showCategoryInTitle,
            hideHopper = hideHopper,
            autohideHopper = autohideHopper,
            hopperGravity = hopperGravity,
            isAnyFilterActive = filterState.isAnyActive,
            sheetOpen = sheetOpen,
            sheetTab = sheetTab,
            overflowOpen = overflowOpen,
            detectedMangaTypes = detectedMangaTypes,
            loggedTrackerNames = loggedTrackerNames,
            onSearchActiveChange = { searchActive = it },
            onSearchQueryChange = { searchQuery = it },
            onHopperGravityChange = { hopperGravityPref.set(it) },
            onOpenFilter = { sheetTab = 0; sheetOpen = true },
            onOpenOverflow = { overflowOpen = true },
            onDismissSheet = { sheetOpen = false },
            onDismissOverflow = { overflowOpen = false },
            onSheetTabChange = { sheetTab = it },
        )
    }
}
