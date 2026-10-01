package reikai.domain.backup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MergedHistoryTest {

    @Test
    fun `a chapter with no stored row takes the incoming read and time`() {
        mergedHistory(readAt = 500, readDuration = 60, storedReadAt = null, storedDuration = 0) shouldBe (500L to 60L)
    }

    @Test
    fun `a row already holding the incoming read gains no time`() {
        mergedHistory(readAt = 500, readDuration = 60, storedReadAt = 500, storedDuration = 60) shouldBe (500L to 0L)
    }

    @Test
    fun `a later stored read is kept while the longer incoming time tops the row up`() {
        mergedHistory(readAt = 500, readDuration = 60, storedReadAt = 900, storedDuration = 20) shouldBe (900L to 40L)
    }

    @Test
    fun `a later incoming read moves the row forward without adding time it already has`() {
        mergedHistory(readAt = 900, readDuration = 20, storedReadAt = 500, storedDuration = 60) shouldBe (900L to 0L)
    }

    @Test
    fun `a stored row with no read time takes the incoming one`() {
        mergedHistory(readAt = 0, readDuration = 5, storedReadAt = null, storedDuration = 5) shouldBe (0L to 0L)
    }
}
