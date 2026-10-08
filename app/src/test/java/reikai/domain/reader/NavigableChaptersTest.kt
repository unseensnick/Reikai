package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The chapters a reader steps through, the same pipeline in the manga and novel readers. */
class NavigableChaptersTest {

    private data class Ch(
        val id: Long,
        val number: Double,
        val hidden: Boolean = false,
        val origin: String? = null,
        val eligible: Boolean = true,
    )

    private fun List<Ch>.navigable(
        current: Ch,
        skipDuplicates: Boolean = true,
        downloadedOnlyIds: Set<Long>? = null,
    ) = navigableChapters(
        current,
        isHidden = { it.hidden },
        isForwardEligible = { it.eligible },
        downloadedOnlyIds = downloadedOnlyIds,
        skipDuplicates = skipDuplicates,
        numberOf = { it.number },
        idOf = { it.id },
        originOf = { it.origin },
        ownerOf = { 1L },
    )

    /** Where a forward step from [from] lands, as both readers walk the list this returns. */
    private fun List<Ch>.nextAfter(from: Ch): Ch? {
        val navigable = navigable(from)
        return navigable.neighbourChapter(navigable.indexOf(from), forward = true) { it.eligible }
    }

    private val one = Ch(id = 1, number = 1.0)
    private val twoHidden = Ch(id = 2, number = 2.0, hidden = true)
    private val twoShown = Ch(id = 3, number = 2.0)
    private val three = Ch(id = 4, number = 3.0)

    /** Removing duplicates first let a hidden copy win its number, and hiding it then lost the chapter. */
    @Test
    fun `a hidden copy never wins a duplicate group`() {
        listOf(one, twoHidden, twoShown, three).navigable(one).map { it.id } shouldBe listOf(1L, 3L, 4L)
    }

    @Test
    fun `Downloaded only keeps the chapters on disk`() {
        listOf(one, twoShown, three).downloadedOrCurrent(one, { it.id }, setOf(1L, 4L)).map { it.id } shouldBe
            listOf(1L, 4L)
    }

    /** A chapter opened from History or Updates may not be on disk, and the reader still needs it. */
    @Test
    fun `Downloaded only keeps the chapter being read when it is not on disk`() {
        listOf(one, twoShown, three).downloadedOrCurrent(twoShown, { it.id }, setOf(4L)).map { it.id } shouldBe
            listOf(3L, 4L)
    }

    /** Opening a hidden chapter directly still has to resolve to something to read. */
    @Test
    fun `the chapter being read stays when it is hidden`() {
        listOf(one, twoHidden, three).navigable(twoHidden, skipDuplicates = false).map { it.id } shouldBe
            listOf(1L, 2L, 4L)
    }

    /** A manga's chapter 5 under two scanlators, only the other one's on disk behind a Downloaded filter. */
    @Test
    fun `a forward step reaches the copy the skip filters let it land on, not the next number`() {
        val fourX = Ch(id = 40, number = 4.0, origin = "x")
        val fiveX = Ch(id = 50, number = 5.0, origin = "x", eligible = false)
        val fiveY = Ch(id = 51, number = 5.0, origin = "y")
        val six = Ch(id = 60, number = 6.0, origin = "x")
        listOf(fourX, fiveX, fiveY, six).nextAfter(fourX) shouldBe fiveY
    }

    /** A novel listing chapter 5 twice with the first copy read, under Skip read. */
    @Test
    fun `a forward step reaches an unread copy listed after a read one`() {
        val four = Ch(id = 40, number = 4.0)
        val fiveRead = Ch(id = 50, number = 5.0, eligible = false)
        val fiveUnread = Ch(id = 51, number = 5.0)
        val six = Ch(id = 60, number = 6.0)
        listOf(four, fiveRead, fiveUnread, six).nextAfter(four) shouldBe fiveUnread
    }

    /** Under Downloaded only a copy off disk leaves the list, so one on disk wins even over a copy a step may land on. */
    @Test
    fun `with Downloaded only the copy on disk wins a duplicate group`() {
        val fiveEligible = Ch(id = 50, number = 5.0, origin = "x")
        val fiveOnDisk = Ch(id = 51, number = 5.0, origin = "y", eligible = false)
        listOf(one, fiveEligible, fiveOnDisk).navigable(one, downloadedOnlyIds = setOf(1L, 51L)).map { it.id } shouldBe
            listOf(1L, 51L)
    }
}
