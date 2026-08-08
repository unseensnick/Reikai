package reikai.presentation.recents

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The updated lane's target rule, and the read lane's. They are one function each rather than one per
 * provider precisely because the two content types drifted on a shared predicate once already (the
 * "started" filter's negated branch), so both sides call these and only the chapter fetch stays per
 * type. A chapter another source of the group already read arrives here as read.
 */
class RecentsTargetTest {

    private val hour = 60 * 60 * 1000L

    private fun chapter(id: Long, read: Boolean = false) = RecentsChapter(id = id, fetchedAt = 0, read = read)

    @Test
    fun `resume reopens the recorded chapter while it is unfinished`() {
        val chapters = listOf(chapter(1, read = true), chapter(2), chapter(3))

        resumeInGroup(chapters, recordedId = 2) shouldBe 2L
    }

    @Test
    fun `a finished recorded chapter moves on to the next unread`() {
        val chapters = listOf(chapter(1, read = true), chapter(2, read = true), chapter(3))

        resumeInGroup(chapters, recordedId = 2) shouldBe 3L
    }

    @Test
    fun `resume skips a chapter another source of the group already read`() {
        val chapters = listOf(chapter(1, read = true), chapter(2, read = true), chapter(3, read = true), chapter(4))

        resumeInGroup(chapters, recordedId = 2) shouldBe 4L
    }

    @Test
    fun `resume never goes back to an unread chapter before the recorded one`() {
        val chapters = listOf(chapter(1), chapter(2, read = true), chapter(3))

        resumeInGroup(chapters, recordedId = 2) shouldBe 3L
    }

    @Test
    fun `the first unread of a group is the oldest one left`() {
        val chapters = listOf(chapter(1, read = true), chapter(2), chapter(3))

        firstUnreadOf(chapters) shouldBe 2L
    }

    @Test
    fun `a group with nothing left to read offers no first unread`() {
        firstUnreadOf(listOf(chapter(1, read = true))) shouldBe null
    }

    @Test
    fun `a fully read group has nothing to resume`() {
        val chapters = listOf(chapter(1, read = true), chapter(2, read = true))

        resumeInGroup(chapters, recordedId = 1) shouldBe null
    }

    @Test
    fun `a recorded chapter the group list does not hold defers to its own source`() {
        val chapters = listOf(chapter(1), chapter(2))

        resumeInGroup(chapters, recordedId = 99) shouldBe null
    }

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
