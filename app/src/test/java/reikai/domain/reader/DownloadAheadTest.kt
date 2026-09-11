package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** What download-ahead queues, the rule the manga reader and both novel readers share. */
class DownloadAheadTest {

    // Reading order; "b" and "d" are read, on their own row or on another source of the group.
    private val chapters = listOf("a", "b", "c", "d", "e", "f")
    private val isRead: (String) -> Boolean = { it in setOf("b", "d") }

    @Test
    fun `a read chapter ahead is passed over rather than queued`() {
        chaptersToDownloadAhead(chapters, from = 1, count = 2, isRead = isRead) shouldBe listOf("c", "e")
    }

    @Test
    fun `a chapter the list does not hold queues nothing`() {
        chaptersToDownloadAhead(chapters, from = -1, count = 2, isRead = isRead) shouldBe emptyList()
    }
}
