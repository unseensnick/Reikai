package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The one skip-duplicate rule both readers now run. The novel reader used to step over duplicates while
 * navigating instead of removing them, so its chapter sheet, download-ahead and delete-after-read each
 * counted chapters the reader would never stop on.
 */
class DuplicateChaptersTest {

    private data class Ch(
        val id: Long,
        val number: Double,
        val origin: String?,
        val owner: Long = 1L,
        val eligible: Boolean = true,
    )

    private fun List<Ch>.dedup(current: Ch) = removeDuplicateChapters(
        current,
        rank = { if (it.eligible) 0 else 1 },
        numberOf = { it.number },
        idOf = { it.id },
        originOf = { it.origin },
        ownerOf = { it.owner },
    )

    private val a1 = Ch(id = 1, number = 1.0, origin = "alpha")
    private val b1 = Ch(id = 2, number = 1.0, origin = "beta")
    private val a2 = Ch(id = 3, number = 2.0, origin = "alpha")
    private val b2 = Ch(id = 4, number = 2.0, origin = "beta")

    @Test
    fun `each chapter number survives exactly once`() {
        listOf(a1, b1, a2, b2).dedup(a1).map { it.number } shouldBe listOf(1.0, 2.0)
    }

    @Test
    fun `the chapter being read is the one kept from its own group`() {
        listOf(a1, b1).dedup(b1).map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `other groups keep the entry from the same origin as the current chapter`() {
        listOf(a1, b1, a2, b2).dedup(b1).map { it.id } shouldBe listOf(2L, 4L)
    }

    @Test
    fun `the chapter being read wins over a sibling from the same origin`() {
        // Origin alone cannot decide this group: both entries are "beta", and the one being read is
        // second. Only matching on identity keeps the reader on the chapter it is already showing.
        val betaTwin = Ch(id = 10, number = 1.0, origin = "beta")
        listOf(betaTwin, b1).dedup(b1).map { it.id } shouldBe listOf(2L)
    }

    @Test
    fun `a group with no entry from that origin falls back to its first`() {
        val orphan = Ch(id = 5, number = 3.0, origin = "gamma")
        listOf(b1, orphan).dedup(b1).map { it.id } shouldBe listOf(2L, 5L)
    }

    @Test
    fun `a list with nothing duplicated is returned unchanged`() {
        listOf(a1, a2).dedup(a1) shouldBe listOf(a1, a2)
    }

    @Test
    fun `two sources of a merged entry keep both chapters, however they number them`() {
        // Across a merge group a number identifies nothing: each source counts its own way, and the
        // stitch has already decided what is one chapter. Collapsing here ate a distinct chapter.
        val fromOne = Ch(id = 20, number = 5.0, origin = "alpha", owner = 1L)
        val fromTwo = Ch(id = 21, number = 5.0, origin = "beta", owner = 2L)

        listOf(fromOne, fromTwo).dedup(fromOne).map { it.id } shouldBe listOf(20L, 21L)
    }

    /** A prologue, a side story and an afterword all read as -1, and each is its own chapter. */
    @Test
    fun `unnumbered chapters are never duplicates of each other`() {
        val prologue = Ch(id = 30, number = -1.0, origin = null)
        val one = Ch(id = 31, number = 1.0, origin = null)
        val sideStory = Ch(id = 32, number = -1.0, origin = null)
        listOf(prologue, one, sideStory).dedup(one).map { it.id } shouldBe listOf(30L, 31L, 32L)
    }

    @Test
    fun `a chapter with no origin keeps the copy that has none either`() {
        // A manga chapter with no scanlator, among copies where some name one. The matching copy sits
        // second, so the fall-back to the first cannot pass for the origin match.
        val named = Ch(id = 6, number = 1.0, origin = "alpha")
        val unnamed = Ch(id = 7, number = 1.0, origin = null)
        listOf(named, unnamed).dedup(Ch(id = 9, number = 5.0, origin = null)).map { it.id } shouldBe listOf(7L)
    }

    /** Skip filtered with a Downloaded filter: only the other scanlator's copy of 5 is on disk. */
    @Test
    fun `a duplicate set keeps a copy a forward step may land on over the same-origin one`() {
        val fourX = Ch(id = 40, number = 4.0, origin = "x")
        val fiveX = Ch(id = 50, number = 5.0, origin = "x", eligible = false)
        val fiveY = Ch(id = 51, number = 5.0, origin = "y")
        listOf(fourX, fiveX, fiveY).dedup(fourX).map { it.id } shouldBe listOf(40L, 51L)
    }

    @Test
    fun `of the copies a forward step may land on, the same origin wins`() {
        val fourX = Ch(id = 40, number = 4.0, origin = "x")
        val fiveZ = Ch(id = 52, number = 5.0, origin = "z")
        val fiveX = Ch(id = 50, number = 5.0, origin = "x")
        listOf(fourX, fiveZ, fiveX).dedup(fourX).map { it.id } shouldBe listOf(40L, 50L)
    }

    @Test
    fun `the chapter being read wins over a copy a forward step may land on`() {
        val readX = Ch(id = 50, number = 5.0, origin = "x", eligible = false)
        val fiveY = Ch(id = 51, number = 5.0, origin = "y")
        listOf(readX, fiveY).dedup(readX).map { it.id } shouldBe listOf(50L)
    }
}
