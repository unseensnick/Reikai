package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.GroupChapterFlags
import tachiyomi.core.common.preference.TriState

/**
 * The forward-eligibility rule every reader runs, over the group's answer. What the chapter-list
 * filters keep is pinned beside the details lists in MergedChapterFilterConformanceTest; this covers
 * the settings only the readers have. Row 1 is the copy shown, row 2 another source's copy of it.
 */
class ReaderChapterFiltersTest {

    private data class Row(val id: Long, val read: Boolean = false)

    private val noFilters = ChapterListFilters(TriState.DISABLED, TriState.DISABLED, TriState.DISABLED)

    private fun flags(siblingRead: Boolean = false, onDisk: Set<Long> = emptySet()) = GroupChapterFlags(
        pooled = listOf(Row(1L), Row(2L, read = siblingRead)),
        shown = listOf(Row(1L)),
        stitch = listOf(ChapterUnit(1L, 0, 0), ChapterUnit(2L, 0, 1)),
        id = { it.id },
        read = { it.read },
        bookmark = { false },
    ) { onDisk }

    @Test
    fun `skip read passes a chapter another source has read`() {
        val flags = flags(siblingRead = true)

        flags.isForwardEligible(Row(1L), skipRead = true, skipFiltered = false, filters = noFilters) shouldBe false
    }

    @Test
    fun `without skip filtered a filter the chapter fails is not consulted`() {
        val flags = flags(siblingRead = true)
        val unreadOnly = noFilters.copy(unread = TriState.ENABLED_IS)

        flags.isForwardEligible(Row(1L), skipRead = false, skipFiltered = false, filters = unreadOnly) shouldBe true
    }

    @Test
    fun `the downloaded filter keeps a chapter whose other copy is on disk`() {
        val flags = flags(onDisk = setOf(2L))
        val downloadedOnly = noFilters.copy(downloaded = TriState.ENABLED_IS)

        flags.isForwardEligible(Row(1L), skipRead = false, skipFiltered = true, filters = downloadedOnly) shouldBe true
    }
}
