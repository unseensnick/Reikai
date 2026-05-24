package yokai.presentation.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.library.filter.FilterBottomSheet
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.chapter.ChapterSort
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.track.interactor.GetTrack
import yokai.presentation.library.manga.MangaLibraryFilter
import yokai.presentation.library.manga.MangaLibraryFilter.MangaFilterState
import yokai.presentation.library.manga.MangaLibraryScreenModel
import yokai.presentation.library.manga.MangaLibrarySearch
import yokai.presentation.library.settings.tabs.columnsForGridValue
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
        val getChapter: GetChapter = remember { Injekt.get() }
        val router = LocalRouter.currentOrThrow
        val coroutineScope = rememberCoroutineScope()

        val libraryLayout by preferences.libraryLayout().collectAsState()
        val uniformGrid by remember { Injekt.get<yokai.domain.ui.UiPreferences>().uniformGrid() }.collectAsState()
        val useStaggeredGrid by preferences.useStaggeredGrid().collectAsState()
        val groupLibraryBy by preferences.groupLibraryBy().collectAsState()
        val collapsedCategories by preferences.collapsedCategories().collectAsState()
        val collapsedCategoriesPref = remember { preferences.collapsedCategories() }
        val showCategoryInTitle by preferences.showCategoryInTitle().collectAsState()
        val showCategoryItemCounts by preferences.categoryNumberOfItems().collectAsState()
        val hideHopper by preferences.hideHopper().collectAsState()
        val autohideHopper by preferences.autohideHopper().collectAsState()
        val hopperLongPressAction by preferences.hopperLongPressAction().collectAsState()
        // Per-cover badge / outline prefs collected reactively so toggles in the Display
        // options sheet propagate to the grid immediately.
        val outlineOnCovers by remember { Injekt.get<yokai.domain.ui.UiPreferences>().outlineOnCovers() }.collectAsState()
        val showDownloadBadge by preferences.downloadBadge().collectAsState()
        val showLanguageBadge by preferences.languageBadge().collectAsState()
        val unreadBadgeType by preferences.unreadBadgeType().collectAsState()
        val showEmptyCategoriesWhileFiltering by preferences.showEmptyCategoriesWhileFiltering().collectAsState()
        val showAllCategories by preferences.showAllCategories().collectAsState()
        val hideStartReadingButton by preferences.hideStartReadingButton().collectAsState()
        val useLargeToolbar by preferences.useLargeToolbar().collectAsState()
        val hopperGravityPref = remember { preferences.hopperGravity() }
        val hopperGravity by hopperGravityPref.changes()
            .collectAsState(initial = hopperGravityPref.get())

        // Column count derived from preferences.gridSize() via the legacy formula (see
        // [columnsForGridValue] for the math). Pref Float maps to a slider int 0..7; we use the
        // current screen width to compute exactly the column count shown in the Display tab's
        // "Portrait: X • Landscape: Y" subtitle, so the user sees the grid render the same N
        // they pick in the picker.
        val gridSizePref by preferences.gridSize().collectAsState()
        val sliderValue = ((gridSizePref + 0.5f) * 2f).coerceIn(0f, 7f)
        val columns = columnsForGridValue(sliderValue, LocalConfiguration.current.screenWidthDp)

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
            key3 = showAllCategories,
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
                    keepEmptyCategories = showAllCategories,
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

        // Filter pipeline for category visibility:
        //
        //  - showAllCategories = true → always keep every category key, even if no manga match.
        //    The filter call already respects this via keepEmptyCategories, but search has its
        //    own .filterValues drop, so we also re-introduce keys at the screen level. This
        //    covers the search-only path where the filter never runs.
        //  - showAllCategories = false + showEmptyCategoriesWhileFiltering = true while
        //    actively narrowing (search query or active filter) → re-introduce empties so the
        //    user keeps category headers visible while they hunt for a specific result.
        //  - Otherwise → drop empties (current default).
        val postFilterLibrary = when {
            showAllCategories ->
                library.mapValues { (cat, _) -> filteredLibrary[cat].orEmpty() }
            showEmptyCategoriesWhileFiltering &&
                (searchQuery.isNotEmpty() || filterState.isAnyActive) ->
                library.mapValues { (cat, _) -> filteredLibrary[cat].orEmpty() }
            else -> filteredLibrary
        }

        // Header counts are sourced from the pre-collapse, post-filter map so collapsing a
        // category does not zero out its header. Counts shown on the header therefore reflect
        // how many of the user's filtered items live in that category.
        val displayedHeaderCounts = remember(postFilterLibrary) {
            postFilterLibrary.entries.associate { (cat, items) -> (cat.id ?: 0) to items.size }
        }

        // Collapse only kicks in for default grouping (groupLibraryBy = BY_DEFAULT). Dynamic
        // groupings have their own collapse pref (collapsedDynamicCategories) which Phase 6
        // will wire alongside multi-source grouping; until then the header is non-interactive
        // for dynamic groups.
        val collapsible = groupLibraryBy == eu.kanade.tachiyomi.ui.library.LibraryGroup.BY_DEFAULT
        val collapsedIds = remember(collapsedCategories) {
            collapsedCategories.mapNotNullTo(HashSet()) { it.toIntOrNull() }
        }
        val displayedLibrary = if (collapsible && collapsedIds.isNotEmpty()) {
            postFilterLibrary.mapValues { (cat, items) ->
                if (cat.id != null && cat.id in collapsedIds) emptyList() else items
            }
        } else {
            postFilterLibrary
        }

        LibraryContent(
            library = displayedLibrary,
            allCategories = allCategories,
            categoryItemCounts = categoryItemCounts,
            displayedHeaderCounts = displayedHeaderCounts,
            collapsedIds = collapsedIds,
            collapsibleHeaders = collapsible,
            showCategoryItemCounts = showCategoryItemCounts,
            columns = columns,
            libraryLayout = libraryLayout,
            uniformGrid = uniformGrid,
            useStaggeredGrid = useStaggeredGrid,
            searchActive = searchActive,
            searchQuery = searchQuery,
            showCategoryInTitle = showCategoryInTitle,
            hideHopper = hideHopper,
            autohideHopper = autohideHopper,
            hopperGravity = hopperGravity,
            outlineOnCovers = outlineOnCovers,
            showDownloadBadge = showDownloadBadge,
            showLanguageBadge = showLanguageBadge,
            unreadBadgeType = unreadBadgeType,
            hideStartReadingButton = hideStartReadingButton,
            useLargeToolbar = useLargeToolbar,
            isAnyFilterActive = filterState.isAnyActive,
            sheetOpen = sheetOpen,
            sheetTab = sheetTab,
            overflowOpen = overflowOpen,
            detectedMangaTypes = detectedMangaTypes,
            loggedTrackerNames = loggedTrackerNames,
            onSearchActiveChange = { searchActive = it },
            onSearchQueryChange = { searchQuery = it },
            onHopperGravityChange = { hopperGravityPref.set(it) },
            onToggleCategoryCollapse = { category ->
                val id = category.id?.toString() ?: return@LibraryContent
                val current = collapsedCategoriesPref.get().toMutableSet()
                if (!current.add(id)) current.remove(id)
                collapsedCategoriesPref.set(current)
            },
            hopperLongPressAction = hopperLongPressAction,
            onExpandCollapseAllCategories = {
                // Mirror the Categories tab Expand/Collapse all toggle: when nothing is
                // collapsed, collapse every category id; otherwise clear the set. Only
                // meaningful under BY_DEFAULT grouping, but the long-press action is global so
                // we apply the toggle unconditionally; under dynamic grouping the pref simply
                // has no visible effect until the user switches back to default.
                val all = library.keys.mapNotNull { it.id?.toString() }.toSet()
                val current = collapsedCategoriesPref.get()
                collapsedCategoriesPref.set(if (current.isEmpty()) all else emptySet())
            },
            onOpenSheetAt = { tabIndex ->
                sheetTab = tabIndex
                sheetOpen = true
            },
            onOpenRandomSeries = {
                // Random pick from the post-collapse displayed library: collapsed categories
                // are implicitly hidden, so picking a manga the user just chose to hide would
                // feel inconsistent. Empty library (no favorited manga) silently no-ops.
                val pool = displayedLibrary.values.asSequence().flatten().toList()
                if (pool.isNotEmpty()) {
                    val random = pool.random().libraryManga.manga
                    router.pushController(MangaDetailsController(random).withFadeTransaction())
                }
            },
            onContinueReading = { manga ->
                // Mirror legacy LibraryController.startReading: load all chapters, pick the
                // next-unread via ChapterSort, then launch ReaderActivity. Manga with no
                // remaining unread chapters silently no-op (the button is gated on unread > 0
                // upstream so this is only reachable in a race where the count changes).
                coroutineScope.launch {
                    val chapters = getChapter.awaitAll(manga)
                    val next = ChapterSort(manga).getNextUnreadChapter(chapters, false)
                        ?: return@launch
                    val activity = router.activity ?: return@launch
                    activity.startActivity(ReaderActivity.newIntent(activity, manga, next))
                }
            },
            onOpenFilter = { sheetTab = 0; sheetOpen = true },
            onOpenOverflow = { overflowOpen = true },
            onDismissSheet = { sheetOpen = false },
            onDismissOverflow = { overflowOpen = false },
            onSheetTabChange = { sheetTab = it },
        )
    }
}
