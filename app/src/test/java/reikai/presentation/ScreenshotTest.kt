package reikai.presentation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import eu.kanade.core.preference.asState
import eu.kanade.tachiyomi.ui.library.LibraryItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import reikai.domain.entry.EntryId
import reikai.presentation.browse.catalogue.SavedSearchChip
import reikai.presentation.library.LibraryBucket
import reikai.presentation.library.LibraryItemsLayout
import reikai.presentation.library.ManualOrderDrag
import reikai.presentation.library.ReikaiLibraryContent
import reikai.presentation.migrate.flow.EntryMigrationListViewModel.CommitActivity
import reikai.presentation.migrate.flow.EntryMigrationListViewModel.Dialog
import reikai.presentation.migrate.flow.EntryMigrationListViewModel.State
import reikai.presentation.migrate.flow.MigrationDataFlag
import reikai.presentation.migrate.flow.MigrationListDialogs
import reikai.presentation.reader.NeighbourChapterRetry
import reikai.presentation.reader.settings.MangaSheetProbe
import reikai.presentation.reader.settings.NovelSheetProbe
import reikai.presentation.reader.settings.ReaderSettingsSheet
import reikai.presentation.recents.RecentsToolbar
import reikai.presentation.settings.appearance.IconTile
import reikai.util.LauncherIcon
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.presentation.core.components.material.TabText

/**
 * Pixel snapshots of the screens the UI tests cover, compared against src/test/screenshots by CI.
 * Plain `./gradlew test` renders them without comparing; see docs/dev/testing.md.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h640dp-xhdpi")
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        ComposeTestHost {
            Surface(Modifier.fillMaxSize()) {}
            content()
        }
    }

    // The capture itself does not wait for Compose, so a pager or dialog still animating is caught mid-way.
    private fun capture() {
        compose.waitForIdle()
        captureScreenRoboImage()
    }

    private fun showLibraryTabs() = show {
        PrimaryScrollableTabRow(selectedTabIndex = 0) {
            listOf("Reading" to 5, "Plan to read" to 12, "Completed" to 40).forEachIndexed { index, (name, count) ->
                Tab(selected = index == 0, onClick = {}, text = { TabText(name, count) })
            }
        }
    }

    private fun showMangaSheetFilters() {
        show { ReaderSettingsSheet(MangaSheetProbe().pages(), false, {}, null, {}, {}, {}) }
        compose.onNodeWithContentDescription("Filters").performClick()
    }

    private fun showMigrationConfirm() {
        // Two of the five saved, so the image carries checked and unchecked boxes.
        val confirm = Dialog.Confirm(
            replace = true,
            count = 3,
            untouched = 1,
            applicableFlags = MigrationDataFlag.entries.toSet(),
            savedFlags = setOf(MigrationDataFlag.CHAPTER, MigrationDataFlag.CATEGORY),
            loadingFlags = false,
        )
        show { MigrationListDialogs(State(dialog = confirm), {}, {}, { _, _ -> }, {}) }
    }

    @Test
    fun `library tabs`() {
        showLibraryTabs()
        capture()
    }

    // The Mihon style's disc is in its background layer, so a tile that crops only the foreground shrinks it.
    @Test
    fun `app icon tiles`() {
        show {
            Row {
                listOf(LauncherIcon.FLAME_R_STABLE, LauncherIcon.MIHON_RE_STABLE, LauncherIcon.MIHON_RE_OUTLINE)
                    .forEach { IconTile(it, isShown = it == LauncherIcon.MIHON_RE_STABLE) {} }
            }
        }
        capture()
    }

    @Test
    @Config(qualifiers = "+night")
    fun `library tabs dark`() {
        showLibraryTabs()
        capture()
    }

    @Test
    @Config(qualifiers = TABLET)
    fun `library tabs tablet`() {
        showLibraryTabs()
        capture()
    }

    @Test
    fun `migration exit confirm`() {
        show { MigrationListDialogs(State(dialog = Dialog.ExitConfirm), {}, {}, { _, _ -> }, {}) }
        capture()
    }

    @Test
    fun `migration commit confirm`() {
        showMigrationConfirm()
        capture()
    }

    @Test
    @Config(qualifiers = "+night")
    fun `migration commit confirm dark`() {
        showMigrationConfirm()
        capture()
    }

    @Test
    fun `migration progress`() {
        show {
            MigrationListDialogs(State(commit = CommitActivity.Batch(done = 2, total = 5)), {}, {}, { _, _ -> }, {})
        }
        capture()
    }

    @Test
    fun `manga reader settings filters`() {
        showMangaSheetFilters()
        capture()
    }

    @Test
    @Config(qualifiers = "+night")
    fun `manga reader settings filters dark`() {
        showMangaSheetFilters()
        capture()
    }

    @Test
    @Config(qualifiers = TABLET)
    fun `manga reader settings filters tablet`() {
        showMangaSheetFilters()
        capture()
    }

    @Test
    fun `novel reader settings reading`() {
        show { ReaderSettingsSheet(NovelSheetProbe().pages(), false, {}, null, {}, {}, {}) }
        capture()
    }

    @Test
    fun `reader failed chapter retry`() {
        val next = ReaderChapter(Chapter.create()).apply { state = ReaderChapter.State.Error(Exception("HTTP 503")) }
        show { NeighbourChapterRetry(prevChapter = null, nextChapter = next, onRetry = {}) }
        capture()
    }

    @Test
    fun `recents selection bar`() {
        show {
            RecentsToolbar(
                title = "History",
                query = null,
                onQueryChange = {},
                selectionCount = 3,
                onCancelSelection = {},
                onSelectAll = {},
                onInvertSelection = {},
                filterActive = false,
                onFilterClicked = {},
                showsCalendar = false,
                onCalendarClicked = {},
                showsRefresh = false,
                onRefresh = {},
                showsClearHistory = false,
                onClearHistory = {},
                onStatsClicked = {},
            )
        }
        capture()
    }

    @Test
    fun `saved search chips`() {
        show {
            Row {
                SavedSearchChip(selected = true, label = "Completed romance", onClick = {}, onLongClick = {})
                SavedSearchChip(selected = false, label = "New this week", onClick = {}, onLongClick = {})
            }
        }
        capture()
    }

    // A manga and a novel share row id 2, the case the lazy keys and the selection must keep apart.
    private val libraryRows = listOf(
        libraryRow(EntryId.Manga(1), "Alpha", unread = 3, downloads = 2),
        libraryRow(EntryId.Manga(2), "Beta", unread = 0, downloads = 0),
        libraryRow(EntryId.Novel(2), "Gamma", unread = 12, downloads = 1),
        libraryRow(EntryId.Novel(4), "Delta", unread = 0, downloads = 0),
    )

    private fun libraryRow(id: EntryId, title: String, unread: Long, downloads: Int) = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = id.rawId, title = title),
            categories = emptyList(),
            totalChapters = 20,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = null,
            chapterFetchedAt = null,
            lastRead = null,
        ),
        downloadCount = downloads,
        unreadCount = unread,
        isLocal = false,
        badges = LibraryItem.Badges(
            downloadCount = downloads,
            unreadCount = unread,
            isLocal = false,
            sourceLanguage = "",
        ),
        entryId = id,
    )

    // The pager's page: one selected, a query showing the global search row, a Manual category's drag.
    private fun showLibraryItems(
        displayMode: LibraryDisplayMode,
        columns: Int = 3,
        searchQuery: String? = "a",
        manual: Boolean = true,
    ) = show {
        val scope = rememberCoroutineScope()
        LibraryItemsLayout(
            items = libraryRows,
            displayMode = displayMode,
            getColumnsForOrientation = { InMemoryPreferenceStore().getInt("columns", columns).asState(scope) },
            contentPadding = PaddingValues(),
            selection = setOf(EntryId.Novel(2)),
            onClick = {},
            onLongClick = {},
            onClickContinueReading = {},
            searchQuery = searchQuery,
            onGlobalSearchClicked = {},
            drag = if (manual) ManualOrderDrag { _, _ -> } else null,
        )
    }

    @Test
    fun `library comfortable grid`() {
        showLibraryItems(LibraryDisplayMode.ComfortableGrid)
        capture()
    }

    @Test
    fun `library comfortable grid adaptive`() {
        showLibraryItems(LibraryDisplayMode.ComfortableGrid, columns = 0, searchQuery = null, manual = false)
        capture()
    }

    @Test
    fun `library panorama grid`() {
        showLibraryItems(LibraryDisplayMode.ComfortableGridPanorama)
        capture()
    }

    @Test
    fun `library compact grid`() {
        showLibraryItems(LibraryDisplayMode.CompactGrid)
        capture()
    }

    @Test
    fun `library cover only grid`() {
        showLibraryItems(LibraryDisplayMode.CoverOnlyGrid, columns = 0)
        capture()
    }

    @Test
    fun `library list`() {
        showLibraryItems(LibraryDisplayMode.List)
        capture()
    }

    @Test
    fun `library list without search`() {
        showLibraryItems(LibraryDisplayMode.List, searchQuery = null, manual = false)
        capture()
    }

    @Test
    fun `library single list comfortable`() {
        val reading = LibraryBucket.Dynamic("reading", "Reading")
        val planned = LibraryBucket.Dynamic("planned", "Plan to read")
        show {
            ReikaiLibraryContent(
                buckets = listOf(reading, planned),
                getItemsForCategory = { if (it == reading) libraryRows.take(3) else libraryRows.drop(3) },
                collapsedCategories = emptySet(),
                collapsedDynamicCategories = emptySet(),
                getItemCount = { null },
                displayMode = LibraryDisplayMode.ComfortableGrid,
                columns = 3,
                selection = setOf(EntryId.Novel(2)),
                searchQuery = "a",
                gridState = rememberLazyGridState(),
                contentPadding = PaddingValues(),
                onClickManga = { _, _ -> },
                onLongClickManga = { _, _ -> },
                onToggleDefaultCollapse = {},
                onToggleDynamicCollapse = {},
                onGlobalSearchClicked = {},
                onRefresh = { false },
                refreshing = false,
                onClickCategorySort = {},
                onRefreshCategory = {},
                onSelectAllInCategory = {},
                sortLabelFor = { error("a dynamic group has no sort") },
                sortAscendingFor = { null },
                onClickContinueReading = {},
                manualDragFor = { bucket -> ManualOrderDrag { _, _ -> }.takeIf { bucket == reading } },
            )
        }
        capture()
    }

    private companion object {
        // A landscape tablet at medium density, so the image stays small.
        const val TABLET = "w1280dp-h800dp-land-mdpi"
    }
}
