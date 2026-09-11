package reikai.domain.merge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The group's answer for a row every reader surface reads: flagged when any source's copy is. Row 1 is
 * the copy the merged list shows; row 2 is another source's copy of the same chapter.
 */
class GroupChapterFlagsTest {

    private data class Row(val id: Long, val read: Boolean = false, val bookmark: Boolean = false)

    private val stitch = listOf(ChapterUnit(1L, 0, 0), ChapterUnit(2L, 0, 1))

    private fun flags(sibling: Row, onDisk: Set<Long> = emptySet(), stitch: List<ChapterUnit> = this.stitch) =
        GroupChapterFlags(
            pooled = listOf(Row(1L), sibling),
            shown = listOf(Row(1L)),
            stitch = stitch,
            id = { it.id },
            read = { it.read },
            bookmark = { it.bookmark },
        ) { onDisk }

    @Test
    fun `a chapter another source has read reads as read`() {
        flags(Row(2L, read = true)).isRead(Row(1L)) shouldBe true
    }

    @Test
    fun `a chapter another source has bookmarked reads as bookmarked`() {
        flags(Row(2L, bookmark = true)).isBookmarked(Row(1L)) shouldBe true
    }

    @Test
    fun `a chapter whose other copy is on disk reads as downloaded`() {
        flags(Row(2L), onDisk = setOf(2L)).isDownloaded(Row(1L)) shouldBe true
    }

    @Test
    fun `an ungrouped chapter answers for itself`() {
        flags(Row(2L, read = true), stitch = emptyList()).isRead(Row(1L)) shouldBe false
    }

    @Test
    fun `asking for read never probes the disk`() {
        var probed = false
        val flags = GroupChapterFlags(
            pooled = listOf(Row(1L)),
            shown = listOf(Row(1L)),
            stitch = emptyList(),
            id = { it.id },
            read = { it.read },
            bookmark = { it.bookmark },
        ) { emptySet<Long>().also { probed = true } }

        flags.isRead(Row(1L))

        probed shouldBe false
    }
}
