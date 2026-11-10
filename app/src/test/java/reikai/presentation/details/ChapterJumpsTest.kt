package reikai.presentation.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.details.ReadButtonSide
import reikai.presentation.ComposeTestHost

/**
 * The details toolbar's scroll actions over a list shaped like the details body: a run of header items,
 * then the chapter rows last. Each action must land on its row from the top bar and from the menu.
 */
@RunWith(RobolectricTestRunner::class)
class ChapterJumpsTest {

    @get:Rule
    val compose = createComposeRule()

    private var noTargetCalls = 0

    private fun show(nextUnreadId: Long?, inMenu: Boolean = false, topPadding: Dp = 0.dp) = compose.setContent {
        ComposeTestHost {
            val listState = rememberLazyListState()
            val jumps = rememberChapterJumps(listState, ROWS, nextUnreadId) { noTargetCalls++ }
            Column(Modifier.fillMaxSize()) {
                EntryToolbar(
                    title = "Series",
                    hasFilters = false,
                    navigateUp = {},
                    onClickFilter = {},
                    onClickRefresh = {},
                    onClickEditCategory = null,
                    onClickEditNotes = {},
                    onClickShare = null,
                    onClickManageSources = null,
                    onClickMigrate = null,
                    onClickDownload = null,
                    jumps = jumps,
                    jumpsInMenu = inMenu,
                    actionModeCounter = 0,
                    onCancelActionMode = {},
                    onSelectAll = {},
                    onInvertSelection = {},
                    showHidden = false,
                    hasHiddenChapters = false,
                    allHiddenSelected = false,
                    onHide = {},
                    onUnhide = {},
                    onToggleShowHidden = {},
                    titleAlphaProvider = { 1f },
                    backgroundAlphaProvider = { 1f },
                )
                LazyColumn(
                    Modifier.weight(1f).testTag(LIST),
                    state = listState,
                    contentPadding = PaddingValues(top = topPadding),
                ) {
                    items(HEADER_ITEMS) { Text("Header $it", Modifier.height(120.dp)) }
                    items(ROWS) { row ->
                        val id = (row as EntryChapterListItem.Chapter).id
                        Text("Chapter $id", Modifier.height(48.dp))
                    }
                }
            }
        }
    }

    @Test
    fun `Scroll to bottom shows the last chapter`() {
        show(nextUnreadId = null)

        compose.onNodeWithContentDescription("Scroll to bottom").performClick()

        compose.onNodeWithText("Chapter $LAST").assertIsDisplayed()
    }

    @Test
    fun `Scroll to next unread shows that chapter past the header items`() {
        show(nextUnreadId = TARGET)

        compose.onNodeWithContentDescription("Scroll to next unread").performClick()

        compose.onNodeWithText("Chapter $TARGET").assertIsDisplayed()
    }

    // The two-pane list pads its top under the app bar; the target still lands mid-list, not a padding lower.
    @Test
    fun `Scroll to next unread puts that chapter mid-list under top padding`() {
        show(nextUnreadId = TARGET, topPadding = 120.dp)

        compose.onNodeWithContentDescription("Scroll to next unread").performClick()

        val list = compose.onNodeWithTag(LIST).getBoundsInRoot()
        val rowTop = compose.onNodeWithText("Chapter $TARGET").getBoundsInRoot().top
        val listMiddle = list.top + (list.bottom - list.top) / 2
        (rowTop - listMiddle).value.let { kotlin.math.abs(it) } shouldBeLessThan 24f
    }

    @Test
    fun `Scroll to top shows the first header item again`() {
        show(nextUnreadId = null)
        compose.onNodeWithContentDescription("Scroll to bottom").performClick()

        compose.onNodeWithContentDescription("Scroll to top").performClick()

        compose.onNodeWithText("Header 0").assertIsDisplayed()
    }

    @Test
    fun `a next unread chapter the list does not hold reports there is none`() {
        show(nextUnreadId = 9_999L)

        compose.onNodeWithContentDescription("Scroll to next unread").performClick()

        noTargetCalls shouldBe 1
    }

    @Test
    fun `no unread chapter at all reports there is none`() {
        show(nextUnreadId = null)

        compose.onNodeWithContentDescription("Scroll to next unread").performClick()

        noTargetCalls shouldBe 1
    }

    @Test
    fun `the menu placement jumps from the overflow`() {
        show(nextUnreadId = TARGET, inMenu = true)

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Scroll to next unread").performClick()

        compose.onNodeWithText("Chapter $TARGET").assertIsDisplayed()
    }

    @Test
    fun `the menu placement leaves no scroll icons in the top bar`() {
        show(nextUnreadId = TARGET, inMenu = true)

        compose.onAllNodesWithContentDescription("Scroll to bottom").fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `a button let go left of the middle docks left`() {
        dockedSide(centerX = 499f, width = 1000) shouldBe ReadButtonSide.LEFT
    }

    @Test
    fun `a button let go right of the middle docks right`() {
        dockedSide(centerX = 501f, width = 1000) shouldBe ReadButtonSide.RIGHT
    }

    private companion object {
        const val LIST = "chapter-list"
        const val HEADER_ITEMS = 10
        const val LAST = 80L
        const val TARGET = 40L
        val ROWS: List<EntryChapterListItem> = (1L..LAST).map { id ->
            EntryChapterListItem.Chapter(
                id = id,
                name = "Chapter $id",
                subtitle = null,
                read = id < TARGET,
                bookmark = false,
                dateUpload = null,
                chapterNumber = id.toDouble(),
                progress = null,
                downloadState = Download.State.NOT_DOWNLOADED,
                numberHinted = false,
            )
        }
    }
}
