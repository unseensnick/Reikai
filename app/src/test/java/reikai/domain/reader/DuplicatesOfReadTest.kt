package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * What finishing a chapter marks read with it. Within one entry the number decides, as upstream does,
 * so another scanlator's copy is reached; across a merge group only the stitch does.
 */
class DuplicatesOfReadTest {

    private data class Ch(val id: Long, val number: Double, val owner: Long = 1L)

    private fun List<Ch>.duplicatesOf(read: Ch, stitchCopies: Set<Long> = emptySet()) = duplicatesOfRead(
        read,
        stitchCopies,
        numberOf = { it.number },
        idOf = { it.id },
        ownerOf = { it.owner },
    ).map { it.id }

    private val alphaFive = Ch(id = 1, number = 5.0)
    private val betaFive = Ch(id = 2, number = 5.0)
    private val six = Ch(id = 3, number = 6.0)

    @Test
    fun `another scanlator's copy in the same entry is marked with it`() {
        listOf(alphaFive, betaFive, six).duplicatesOf(alphaFive) shouldBe listOf(2L)
    }

    @Test
    fun `a chapter without a recognised number marks nothing by number`() {
        val unnumbered = Ch(id = 4, number = -1.0)
        val otherUnnumbered = Ch(id = 5, number = -1.0)

        listOf(unnumbered, otherUnnumbered).duplicatesOf(unnumbered) shouldBe emptyList()
    }

    @Test
    fun `a number stored as a float matches the same number parsed as a double`() {
        // A source-reported number arrives as a 32-bit float, a parsed one as a double.
        val reported = Ch(id = 6, number = 1.1f.toDouble())
        val parsed = Ch(id = 7, number = 1.1)

        listOf(reported, parsed).duplicatesOf(reported) shouldBe listOf(7L)
    }

    @Test
    fun `a sibling source's chapter with the same number is left alone`() {
        // Two sources of one series count their own way, so only the stitch can pair them.
        val siblingFive = Ch(id = 8, number = 5.0, owner = 2L)

        listOf(alphaFive, siblingFive).duplicatesOf(alphaFive) shouldBe emptyList()
    }

    @Test
    fun `the stitch's copy on a sibling source is marked whatever it is numbered`() {
        val siblingCopy = Ch(id = 9, number = 7.0, owner = 2L)

        listOf(alphaFive, siblingCopy).duplicatesOf(alphaFive, stitchCopies = setOf(1L, 9L)) shouldBe listOf(9L)
    }
}
