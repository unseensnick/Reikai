package reikai.presentation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import reikai.presentation.browse.catalogue.SavedSearchChip
import reikai.presentation.migrate.flow.EntryMigrationListViewModel.CommitActivity
import reikai.presentation.migrate.flow.EntryMigrationListViewModel.Dialog
import reikai.presentation.migrate.flow.EntryMigrationListViewModel.State
import reikai.presentation.migrate.flow.MigrationListDialogs
import reikai.presentation.reader.NeighbourChapterRetry
import reikai.presentation.reader.settings.MangaSheetProbe
import reikai.presentation.reader.settings.NovelSheetProbe
import reikai.presentation.reader.settings.ReaderSettingsSheet
import reikai.presentation.recents.RecentsToolbar
import tachiyomi.domain.chapter.model.Chapter
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

    @Test
    fun `library tabs`() {
        show {
            PrimaryScrollableTabRow(selectedTabIndex = 0) {
                listOf("Reading" to 5, "Plan to read" to 12, "Completed" to 40).forEachIndexed { index, (name, count) ->
                    Tab(selected = index == 0, onClick = {}, text = { TabText(name, count) })
                }
            }
        }
        captureScreenRoboImage()
    }

    @Test
    fun `migration exit confirm`() {
        show { MigrationListDialogs(State(dialog = Dialog.ExitConfirm), {}, {}, { _, _ -> }, {}) }
        captureScreenRoboImage()
    }

    @Test
    fun `migration commit confirm`() {
        val confirm = Dialog.Confirm(replace = true, count = 3, untouched = 1, loadingFlags = false)
        show { MigrationListDialogs(State(dialog = confirm), {}, {}, { _, _ -> }, {}) }
        captureScreenRoboImage()
    }

    @Test
    fun `migration progress`() {
        show {
            MigrationListDialogs(State(commit = CommitActivity.Batch(done = 2, total = 5)), {}, {}, { _, _ -> }, {})
        }
        captureScreenRoboImage()
    }

    @Test
    fun `manga reader settings filters`() {
        show { ReaderSettingsSheet(MangaSheetProbe().pages(), false, {}, null, {}, {}, {}) }
        compose.onNodeWithContentDescription("Filters").performClick()
        captureScreenRoboImage()
    }

    @Test
    fun `novel reader settings reading`() {
        show { ReaderSettingsSheet(NovelSheetProbe().pages(), false, {}, null, {}, {}, {}) }
        captureScreenRoboImage()
    }

    @Test
    fun `reader failed chapter retry`() {
        val next = ReaderChapter(Chapter.create()).apply { state = ReaderChapter.State.Error(Exception("HTTP 503")) }
        show { NeighbourChapterRetry(prevChapter = null, nextChapter = next, onRetry = {}) }
        captureScreenRoboImage()
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
            )
        }
        captureScreenRoboImage()
    }

    @Test
    fun `saved search chips`() {
        show {
            Row {
                SavedSearchChip(selected = true, label = "Completed romance", onClick = {}, onLongClick = {})
                SavedSearchChip(selected = false, label = "New this week", onClick = {}, onLongClick = {})
            }
        }
        captureScreenRoboImage()
    }
}
