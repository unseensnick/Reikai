package reikai.presentation.selection

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
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

    private fun downloadOffers(vararg downloads: Download.State?) = chapterSelectionOffers(
        emptyList(),
        downloads.toList(),
    )

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

    /** Each row's own control downloads a finished chapter, and so does upstream's Updates bar. */
    @Test
    fun `a finished chapter not on disk can be downloaded from the selection`() {
        downloadOffers(Download.State.NOT_DOWNLOADED).download shouldBe true
    }

    @Test
    fun `a selection already on disk offers no download`() {
        downloadOffers(Download.State.DOWNLOADED).download shouldBe false
    }

    @Test
    fun `a selection already on disk offers delete`() {
        downloadOffers(Download.State.DOWNLOADED).delete shouldBe true
    }

    @Test
    fun `a selected row with no chapter offers no download`() {
        downloadOffers(null).download shouldBe false
    }

    companion object {
        @JvmStatic
        fun rows() = listOf(RowProbe.RECENTS, RowProbe.DETAILS)

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
                dateUpload = 0L,
                chapterNumber = 1.0,
                progress = progress.takeIf { !read },
                downloadState = Download.State.NOT_DOWNLOADED,
                downloadProgress = 0,
            )
        }
    }
}
