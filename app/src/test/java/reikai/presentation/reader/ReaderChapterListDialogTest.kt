package reikai.presentation.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import reikai.presentation.details.ChapterVolume
import reikai.presentation.details.VolumeLabel
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
        held: Set<Long> = setOf(current),
    ) {
        compose.setContent {
            ComposeTestHost {
                ReaderChapterListDialog(
                    onDismissRequest = {},
                    rows = rows,
                    currentChapterId = current,
                    heldChapterIds = held,
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

    // A strip still draws the neighbour's pages from disk beside the chapter being read.
    @Test
    fun `delete leaves out a neighbour the reader still holds`() {
        show(
            listOf(
                row(1, Download.State.DOWNLOADED),
                row(2, Download.State.DOWNLOADED),
                row(3, Download.State.DOWNLOADED),
            ),
            current = 2,
            held = setOf(1, 2),
        )

        longPress("Chapter 1")
        compose.onNodeWithText("Chapter 3").performClick()
        tap("Delete")
        compose.onNodeWithText("OK").performClick()

        downloads shouldBe listOf(listOf(3L) to ChapterDownloadAction.DELETE)
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

    private fun inVolume(row: ReaderChapterRow, key: String, number: Double = 3.0) =
        row.copy(volume = ChapterVolume("Series", key, VolumeLabel.Number(number), prefix = ""))

    /** Neither provider re-emits its rows on a mark, so the header has to count what the sheet just marked. */
    @Test
    fun `a volume marked read from its header unmarks on the next tap`() {
        show(listOf(inVolume(row(1), "a"), inVolume(row(2), "a"), inVolume(row(3), "b")), current = 3)

        compose.onAllNodesWithContentDescription("Mark as read")[0].performClick()
        compose.onAllNodesWithContentDescription("Mark as unread")[0].performClick()

        marks shouldBe listOf(listOf(1L, 2L) to true, listOf(1L, 2L) to false)
    }

    @Test
    fun `a volume showing an earlier one's label is numbered`() {
        show(listOf(inVolume(row(1), "a"), inVolume(row(2), "b")), current = 2)

        compose.onNodeWithText("Vol. 3 (2)").assertExists()
    }

    /** A download started elsewhere and finished while the sheet is open is on disk, whatever it was deleted as. */
    @Test
    fun `a chapter downloaded again after a delete from the sheet shows as downloaded`() {
        var rows by mutableStateOf(listOf(row(1), row(2, Download.State.DOWNLOADED)))
        compose.setContent {
            ComposeTestHost {
                ReaderChapterListDialog(
                    onDismissRequest = {},
                    rows = rows,
                    currentChapterId = 1,
                    heldChapterIds = setOf(1),
                    undatedChapterDate = UndatedChapterDate.Blank,
                    chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Download,
                    chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Download,
                    onClickChapter = {},
                    onMarkRead = { _, _ -> },
                    onBookmark = { _, _ -> },
                    onDownloadAction = { ids, action -> downloads += ids to action },
                )
            }
        }
        compose.onNodeWithText("Chapter 2").performTouchInput { swipeRight() }
        compose.waitForIdle()
        // The delete, then that chapter queued again and finished, which the queue reports one at a time.
        rows = listOf(row(1), row(2, Download.State.NOT_DOWNLOADED))
        compose.waitForIdle()
        rows = listOf(row(1), row(2, Download.State.QUEUE))
        compose.waitForIdle()
        rows = listOf(row(1), row(2, Download.State.DOWNLOADED))
        compose.waitForIdle()

        longPress("Chapter 2")

        bar("Delete").assertExists()
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
