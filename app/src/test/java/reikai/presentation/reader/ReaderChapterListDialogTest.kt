package reikai.presentation.reader

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.reader.ChapterProgress
import reikai.presentation.ComposeTestHost
import reikai.presentation.components.UndatedChapterDate
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The reader's chapter sheet takes the details screen's long-press selection and bulk bar. The sheet
 * is one component over both content types, which hand it rows already resolved, so this covers both.
 */
@RunWith(RobolectricTestRunner::class)
class ReaderChapterListDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val marks = mutableListOf<Pair<List<Long>, Boolean>>()
    private val bookmarks = mutableListOf<Pair<List<Long>, Boolean>>()
    private val downloads = mutableListOf<Pair<List<Long>, ChapterDownloadAction>>()
    private val opened = mutableListOf<Long>()

    private fun row(id: Long, state: Download.State = Download.State.NOT_DOWNLOADED) = ReaderChapterRow(
        id = id,
        title = "Chapter $id",
        subtitle = null,
        dateUpload = null,
        readProgress = null,
        progress = null,
        read = false,
        bookmark = false,
        downloadState = state,
        downloadProgress = 0,
        offersDownload = true,
    )

    private fun show(
        rows: List<ReaderChapterRow>,
        current: Long = rows.first().id,
        swipe: LibraryPreferences.ChapterSwipeAction = LibraryPreferences.ChapterSwipeAction.Disabled,
    ) {
        compose.setContent {
            ComposeTestHost {
                ReaderChapterListDialog(
                    onDismissRequest = {},
                    rows = rows,
                    currentChapterId = current,
                    undatedChapterDate = UndatedChapterDate.Blank,
                    chapterSwipeStartAction = swipe,
                    chapterSwipeEndAction = swipe,
                    onClickChapter = { opened += it },
                    onMarkRead = { ids, read -> marks += ids to read },
                    onBookmark = { ids, bookmarked -> bookmarks += ids to bookmarked },
                    onDownloadAction = { ids, action -> downloads += ids to action },
                )
            }
        }
    }

    private fun longPress(title: String) = compose.onNodeWithText(title).performTouchInput { longClick() }

    // A row's download control carries the same label as the bar's Download, with a button role the bar's lacks.
    private fun bar(label: String) = compose.onNode(
        hasContentDescription(label) and !SemanticsMatcher.keyIsDefined(SemanticsProperties.Role),
    )

    private fun tap(label: String) = bar(label).performClick()

    @Test
    fun `mark previous as read marks every row above the pressed one`() {
        show(listOf(row(1), row(2), row(3), row(4)))

        longPress("Chapter 3")
        tap("Mark previous as read")

        marks shouldBe listOf(listOf(1L, 2L) to true)
    }

    @Test
    fun `a tap while selecting adds the row instead of opening it`() {
        show(listOf(row(1), row(2), row(3)), current = 3)

        longPress("Chapter 1")
        compose.onNodeWithText("Chapter 2").performClick()
        tap("Mark as read")

        (marks to opened) shouldBe (listOf(listOf(1L, 2L) to true) to emptyList<Long>())
    }

    @Test
    fun `bookmark reaches every selected row`() {
        show(listOf(row(1), row(2)), current = 2)

        longPress("Chapter 1")
        tap("Bookmark chapter")

        bookmarks shouldBe listOf(listOf(1L) to true)
    }

    @Test
    fun `download fetches only the selected rows not on disk`() {
        show(listOf(row(1), row(2, Download.State.DOWNLOADED), row(3)), current = 3)

        longPress("Chapter 1")
        compose.onNodeWithText("Chapter 2").performClick()
        tap("Download")

        downloads shouldBe listOf(listOf(1L) to ChapterDownloadAction.START)
    }

    @Test
    fun `delete leaves out the chapter being read`() {
        show(listOf(row(1, Download.State.DOWNLOADED), row(2, Download.State.DOWNLOADED)), current = 1)

        longPress("Chapter 1")
        compose.onNodeWithText("Chapter 2").performClick()
        tap("Delete")
        compose.onNodeWithText("OK").performClick()

        downloads shouldBe listOf(listOf(2L) to ChapterDownloadAction.DELETE)
    }

    @Test
    fun `the chapter being read alone offers no delete`() {
        show(listOf(row(1, Download.State.DOWNLOADED), row(2)), current = 1)

        longPress("Chapter 1")

        bar("Delete").assertDoesNotExist()
    }

    // The bar leaves it out, so its row's own swipe has to as well.
    @Test
    fun `a delete swipe on the chapter being read leaves its download`() {
        show(
            listOf(row(1, Download.State.DOWNLOADED), row(2, Download.State.DOWNLOADED)),
            current = 1,
            swipe = LibraryPreferences.ChapterSwipeAction.Download,
        )

        compose.onNodeWithText("Chapter 1").performTouchInput { swipeRight() }
        compose.onNodeWithText("Chapter 2").performTouchInput { swipeRight() }
        compose.waitForIdle()

        downloads shouldBe listOf(listOf(2L) to ChapterDownloadAction.DELETE)
    }

    @Test
    fun `a row that has begun offers mark as unread`() {
        show(
            listOf(row(1).copy(progress = ChapterProgress.Pages(lastPageRead = 4, pageCount = 10)), row(2)),
            current = 2,
        )

        longPress("Chapter 1")
        tap("Mark as unread")

        marks shouldBe listOf(listOf(1L) to false)
    }
}
