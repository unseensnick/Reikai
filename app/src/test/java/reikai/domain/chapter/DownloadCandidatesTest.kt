package reikai.domain.chapter

import eu.kanade.presentation.manga.DownloadAction
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.DownloadTargets
import reikai.domain.merge.GroupChapterFlags
import reikai.domain.merge.MergeScope

class DownloadCandidatesTest {

    private val shown = listOf("b", "c")
    private val stored = listOf("a", "b", "c", "d")

    @Test
    fun `skipping filtered chapters picks from the rows on screen`() {
        DownloadCandidates.rows(shown, stored, skipFiltered = true) shouldBe shown
    }

    @Test
    fun `not skipping filtered chapters picks from every stored row`() {
        DownloadCandidates.rows(shown, stored, skipFiltered = false) shouldBe stored
    }

    /** Every caller, manga and novel, details toolbar and library, picks through this one rule. */
    @ParameterizedTest
    @EnumSource(DownloadAction::class)
    fun `a hidden chapter is never queued, whatever the action`(action: DownloadAction) {
        DownloadCandidates.forAction(
            listOf(1, 2, 3),
            action,
            isRead = { false },
            isBookmarked = { true },
            isHidden = { it == 1 },
            isExcluded = { false },
        ).first() shouldBe 2
    }

    @Test
    fun `next N skips read and already downloaded or queued chapters before taking N`() {
        val readingOrder = (1..8).toList()

        DownloadCandidates.forAction(
            readingOrder,
            DownloadAction.NEXT_5_CHAPTERS,
            isRead = { it == 1 },
            isBookmarked = { false },
            isHidden = { false },
            isExcluded = { it == 2 || it == 4 },
        ) shouldBe listOf(3, 5, 6, 7, 8)
    }

    @Test
    fun `bookmarked takes bookmarked chapters read or not, minus the excluded`() {
        DownloadCandidates.forAction(
            listOf(1, 2, 3),
            DownloadAction.BOOKMARKED_CHAPTERS,
            isRead = { it == 1 },
            isBookmarked = { it != 3 },
            isHidden = { false },
            isExcluded = { it == 2 },
        ) shouldBe listOf(1)
    }

    private data class Row(val id: Long, val read: Boolean = false, val bookmark: Boolean = false)

    /** Rows 1 and 2 are two sources' copies of one merged chapter, shown as 1; row 3 stands alone. */
    private fun groupDownload(
        sibling: Row = Row(2L),
        onDisk: Set<Long> = emptySet(),
        queued: Set<Long> = emptySet(),
        action: DownloadAction = DownloadAction.UNREAD_CHAPTERS,
    ): List<Long> {
        val shown = listOf(Row(1L), Row(3L))
        val flags = GroupChapterFlags(
            MergeScope.Group,
            pooled = shown + sibling,
            shown = shown,
            stitch = listOf(ChapterUnit(1L, 0, 0), ChapterUnit(2L, 0, 1), ChapterUnit(3L, 1, 0)),
            id = { it.id },
            read = { it.read },
            bookmark = { it.bookmark },
        ) { onDisk }
        return DownloadCandidates.forGroup(shown, action, flags, DownloadTargets.OWN, shown + sibling, {
            it.id
        }, { false }) {
            it in queued
        }.map { it.id }
    }

    @Test
    fun `a group download skips a chapter another source holds on disk`() {
        groupDownload(onDisk = setOf(2L)) shouldBe listOf(3L)
    }

    @Test
    fun `a group download skips a queued chapter`() {
        groupDownload(queued = setOf(1L)) shouldBe listOf(3L)
    }

    @Test
    fun `a group download counts a chapter another source read as read`() {
        groupDownload(sibling = Row(2L, read = true), action = DownloadAction.NEXT_1_CHAPTER) shouldBe listOf(3L)
    }

    @Test
    fun `a group download takes a chapter another source bookmarked as bookmarked`() {
        groupDownload(sibling = Row(2L, bookmark = true), action = DownloadAction.BOOKMARKED_CHAPTERS) shouldBe
            listOf(1L)
    }

    /**
     * A group of a member whose source is gone (M) and an installed one (A), as the library downloads it.
     * Row 11 is M's copy of a chapter A holds as 21, shown from M; row 12 only M holds.
     */
    private fun missingSourceDownload(shownIds: List<Long>, queued: Set<Long> = emptySet()): List<Long> {
        val pooled = listOf(Row(11L), Row(12L), Row(21L))
        val shown = pooled.filter { it.id in shownIds }
        val stitch = listOf(ChapterUnit(11L, 0, 0), ChapterUnit(21L, 0, 1), ChapterUnit(12L, 1, 0))
        val flags =
            GroupChapterFlags(MergeScope.Group, pooled, shown, stitch, { it.id }, { it.read }, { it.bookmark }) {
                emptySet()
            }
        val targets = DownloadTargets.of(MergeScope.Group, pooled, shown, stitch, { it.id }) { it.id == 21L }
        return DownloadCandidates.forGroup(shown, DownloadAction.UNREAD_CHAPTERS, flags, targets, pooled, { it.id }, {
            false
        }) { it in queued }.map { it.id }
    }

    @Test
    fun `a group download fetches an installed source's copy of a chapter shown from a missing one`() {
        missingSourceDownload(listOf(11L)) shouldBe listOf(21L)
    }

    @Test
    fun `a group download fetches nothing for a chapter only a missing source holds`() {
        missingSourceDownload(listOf(12L)) shouldBe emptyList()
    }

    @Test
    fun `a group download skips a chapter whose installed copy is already queued`() {
        missingSourceDownload(listOf(11L, 12L), queued = setOf(21L)) shouldBe emptyList()
    }
}
