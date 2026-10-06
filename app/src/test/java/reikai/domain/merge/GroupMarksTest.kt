package reikai.domain.merge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The one rule every merged row reads for read and bookmarked: flagged when its own copy is, or when a
 * copy on another source of the group is. Chapter 1 is the shown copy, chapter 2 the sibling's.
 */
class GroupMarksTest {

    private data class Row(val id: Long, val read: Boolean = false, val bookmark: Boolean = false)

    private val stitch = listOf(ChapterUnit(1L, 0, 0), ChapterUnit(2L, 0, 1))

    private fun marks(sibling: Row) =
        GroupMarks.of(listOf(Row(1L), sibling), listOf(Row(1L)), stitch, { it.id }, { it.read }, { it.bookmark })

    @Test
    fun `a chapter read on another source reads as read`() {
        marks(Row(2L, read = true)).isRead(1L, ownRead = false) shouldBe true
    }

    @Test
    fun `a chapter read on its own copy reads as read`() {
        GroupMarks.NONE.isRead(1L, ownRead = true) shouldBe true
    }

    @Test
    fun `a chapter read nowhere reads as unread`() {
        marks(Row(2L)).isRead(1L, ownRead = false) shouldBe false
    }

    @Test
    fun `a chapter bookmarked on another source reads as bookmarked`() {
        marks(Row(2L, bookmark = true)).isBookmarked(1L, ownBookmark = false) shouldBe true
    }

    @Test
    fun `a chapter bookmarked on its own copy reads as bookmarked`() {
        GroupMarks.NONE.isBookmarked(1L, ownBookmark = true) shouldBe true
    }

    @Test
    fun `reading another source does not bookmark the chapter`() {
        marks(Row(2L, read = true)).isBookmarked(1L, ownBookmark = false) shouldBe false
    }
}
