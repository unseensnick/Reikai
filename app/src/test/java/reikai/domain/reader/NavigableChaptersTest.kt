package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The chapters a reader steps through, the same pipeline in the manga and novel readers. */
class NavigableChaptersTest {

    private data class Ch(val id: Long, val number: Double, val hidden: Boolean = false)

    private fun List<Ch>.navigable(current: Ch, skipDuplicates: Boolean = true) = navigableChapters(
        current,
        isHidden = { it.hidden },
        skipDuplicates = skipDuplicates,
        numberOf = { it.number },
        idOf = { it.id },
        originOf = { null },
        ownerOf = { 1L },
    )

    private val one = Ch(id = 1, number = 1.0)
    private val twoHidden = Ch(id = 2, number = 2.0, hidden = true)
    private val twoShown = Ch(id = 3, number = 2.0)
    private val three = Ch(id = 4, number = 3.0)

    /** Removing duplicates first let a hidden copy win its number, and hiding it then lost the chapter. */
    @Test
    fun `a hidden copy never wins a duplicate group`() {
        listOf(one, twoHidden, twoShown, three).navigable(one).map { it.id } shouldBe listOf(1L, 3L, 4L)
    }

    /** Opening a hidden chapter directly still has to resolve to something to read. */
    @Test
    fun `the chapter being read stays when it is hidden`() {
        listOf(one, twoHidden, three).navigable(twoHidden, skipDuplicates = false).map { it.id } shouldBe
            listOf(1L, 2L, 4L)
    }
}
