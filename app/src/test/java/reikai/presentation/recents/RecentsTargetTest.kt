package reikai.presentation.recents

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The updated lane's target rule. It is one function rather than one per provider precisely because
 * the two content types drifted on a shared predicate once already (the "started" filter's negated
 * branch), so both sides call this and only the chapter fetch stays per type.
 */
class RecentsTargetTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun `a burst opens its first unread chapter, not the newest`() {
        val chapters = listOf(
            RecentsChapter(id = 1, fetchedAt = 100 * hour, read = true),
            RecentsChapter(id = 2, fetchedAt = 100 * hour, read = false),
            RecentsChapter(id = 3, fetchedAt = 100 * hour, read = false),
        )

        firstUnreadInBurst(chapters, rowChapterId = 3) shouldBe 2L
    }

    @Test
    fun `a chapter fetched outside the window is not part of the burst`() {
        val chapters = listOf(
            RecentsChapter(id = 1, fetchedAt = 0, read = false),
            RecentsChapter(id = 2, fetchedAt = 100 * hour, read = false),
        )

        firstUnreadInBurst(chapters, rowChapterId = 2) shouldBe 2L
    }

    @Test
    fun `a fully read burst falls back to the row's own chapter`() {
        val chapters = listOf(
            RecentsChapter(id = 1, fetchedAt = 100 * hour, read = true),
            RecentsChapter(id = 2, fetchedAt = 100 * hour, read = true),
        )

        firstUnreadInBurst(chapters, rowChapterId = 2) shouldBe 2L
    }

    @Test
    fun `an unknown row chapter falls back to itself rather than picking a stranger`() {
        val chapters = listOf(RecentsChapter(id = 1, fetchedAt = 100 * hour, read = false))

        firstUnreadInBurst(chapters, rowChapterId = 99) shouldBe 99L
    }

    @Test
    fun `reading order decides which unread chapter is first, not fetch order`() {
        // The provider hands chapters in its own reading order; a source that fetched chapter 2 before
        // chapter 1 must still open chapter 1.
        val chapters = listOf(
            RecentsChapter(id = 1, fetchedAt = 101 * hour, read = false),
            RecentsChapter(id = 2, fetchedAt = 100 * hour, read = false),
        )

        firstUnreadInBurst(chapters, rowChapterId = 2) shouldBe 1L
    }
}
