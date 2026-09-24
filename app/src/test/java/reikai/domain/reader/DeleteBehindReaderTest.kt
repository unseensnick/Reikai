package reikai.domain.reader

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Which chapter "after reading automatically delete" retires in both readers. An off-by-one here
 * deletes the chapter the reader is sitting on.
 */
class DeleteBehindReaderTest {

    private val order = listOf(1L, 2L, 3L, 4L, 5L)

    private fun behind(currentId: Long, slots: Int) = order.chapterToDeleteBehind(currentId, slots) { it }

    @Test
    fun `no slots retires the chapter just finished`() {
        behind(currentId = 4L, slots = 0) shouldBe 4L
    }

    @Test
    fun `one slot retires the chapter one position back`() {
        behind(currentId = 4L, slots = 1) shouldBe 3L
    }

    @Test
    fun `two slots retire the chapter two positions back`() {
        behind(currentId = 4L, slots = 2) shouldBe 2L
    }

    @Test
    fun `nothing is retired before the slots have filled`() {
        behind(currentId = 2L, slots = 3).shouldBeNull()
    }

    @Test
    fun `a chapter missing from the list retires nothing`() {
        behind(currentId = 9L, slots = 0).shouldBeNull()
    }

    @Test
    fun `the setting turned off retires nothing`() {
        behind(currentId = 4L, slots = -1).shouldBeNull()
    }
}
