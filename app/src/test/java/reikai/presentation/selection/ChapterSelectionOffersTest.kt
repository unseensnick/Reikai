package reikai.presentation.selection

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.reader.ChapterProgress
import reikai.presentation.details.EntryChapterListItem
import reikai.presentation.recents.RecentsChapterFiltersTest
import reikai.presentation.recents.StartedProbe
import reikai.presentation.recents.chapterState

/**
 * The bulk bar's offers, pinned once over both progress units and both surfaces' rows. Every unread row
 * carries a progress value, zero included, so a null check offered Mark as unread on a chapter nobody
 * had opened, and the details bar asking its label hid it below one percent.
 */
class ChapterSelectionOffersTest {

    private fun offers(vararg chapters: ChapterMarks) = chapterSelectionOffers(chapters.toList(), emptyList())

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    fun `a chapter never opened has nothing to mark unread`(row: RowProbe) {
        offers(row.make(false, ChapterProgress.Pages(0, pageCount = 38))).markUnread shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("startedRows")
    fun `a chapter opened past its start can be marked unread`(row: RowProbe, probe: StartedProbe) {
        offers(row.make(false, probe.at(3))).markUnread shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    fun `a novel chapter opened under one percent can be marked unread`(row: RowProbe) {
        offers(row.make(false, ChapterProgress.Percent(50))).markUnread shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rows")
    fun `a finished chapter can be marked unread`(row: RowProbe) {
        offers(row.make(true, ChapterProgress.Pages(9, pageCount = 38))).markUnread shouldBe true
    }

    @Test
    fun `a selection answering for no chapter offers no bookmark removal`() {
        offers().removeBookmark shouldBe false
    }

    /** Bookmark and Mark as read ask whether any row lacks the mark, Remove bookmark whether every row has it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("markSelections")
    fun `a selection offers each mark by what its rows carry`(case: String, rows: List<ChapterMarks>, marks: Marks) {
        val offers = chapterSelectionOffers(rows, emptyList())

        Marks(offers.bookmark, offers.removeBookmark, offers.markRead, offers.markUnread) shouldBe marks
    }

    data class Marks(val bookmark: Boolean, val removeBookmark: Boolean, val markRead: Boolean, val markUnread: Boolean)

    /** Each row's own control downloads a finished chapter, and so does upstream's Updates bar. */
    @ParameterizedTest(name = "{0}: download {1}, delete {2}")
    @MethodSource("downloadStates")
    fun `a finished chapter's selection offers Download off the device and Delete on it`(
        state: Download.State?,
        download: Boolean,
        delete: Boolean,
    ) {
        val finished = RowProbe.DETAILS.make(true, ChapterProgress.Pages(9, pageCount = 38))

        val offers = chapterSelectionOffers(listOf(finished), listOf(state))

        offers.download shouldBe download
        offers.delete shouldBe delete
    }

    companion object {
        @JvmStatic
        fun rows() = listOf(RowProbe.RECENTS, RowProbe.DETAILS)

        // Null is a row with no download control.
        @JvmStatic
        fun downloadStates() = listOf(
            Arguments.of(Download.State.NOT_DOWNLOADED, true, false),
            Arguments.of(Download.State.DOWNLOADED, false, true),
            Arguments.of(null, false, false),
        )

        private val unopened = ChapterProgress.Pages(0, pageCount = 38)

        @JvmStatic
        fun markSelections() = listOf(
            Arguments.of(
                "every row read and bookmarked",
                listOf(chapterState(true, bookmark = true, unopened), chapterState(true, bookmark = true, unopened)),
                Marks(bookmark = false, removeBookmark = true, markRead = false, markUnread = true),
            ),
            Arguments.of(
                "one row read and bookmarked, one neither",
                listOf(chapterState(true, bookmark = true, unopened), chapterState(false, bookmark = false, unopened)),
                Marks(bookmark = true, removeBookmark = false, markRead = true, markUnread = true),
            ),
        )

        @JvmStatic
        fun startedRows() = rows().flatMap { row -> RecentsChapterFiltersTest.startedProbes().map { arrayOf(row, it) } }
    }
}

/** One surface's selected row, built the way its adapter builds it: progress hidden once read. */
class RowProbe(private val label: String, val make: (read: Boolean, progress: ChapterProgress) -> ChapterMarks) {
    override fun toString() = label

    companion object {
        val RECENTS = RowProbe("recents") { read, progress -> chapterState(read, bookmark = false, progress) }
        val DETAILS = RowProbe("details") { read, progress ->
            EntryChapterListItem.Chapter(
                id = 1L,
                name = "",
                subtitle = null,
                read = read,
                bookmark = false,
                dateUpload = null,
                chapterNumber = 1.0,
                progress = progress.takeIf { !read },
                downloadState = Download.State.NOT_DOWNLOADED,
                numberHinted = false,
            )
        }
    }
}
