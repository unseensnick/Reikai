package reikai.presentation.recents

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.merge.ChapterUnit

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
    fun `a finished recorded chapter goes back to a backlog left behind it`() {
        val chapters = listOf(chapter(1), chapter(2, read = true), chapter(3))

        resumeInGroup(chapters, recordedId = 2) shouldBe 1L
    }

    @Test
    fun `the newest chapter finished still resumes when earlier ones are unread`() {
        // Caught up on the newest with a backlog behind it. The forward-only answer was null, which is
        // a row the caught-up filter keeps and a tap cannot open.
        val chapters = listOf(chapter(1), chapter(2), chapter(3, read = true))

        resumeInGroup(chapters, recordedId = 3) shouldBe 1L
    }

    @Test
    fun `a newly added row opens the group's first unread`() = runTest {
        addedTarget(listOf(chapter(1, read = true), chapter(2))) { listOf(chapter(9)) } shouldBe 2L
    }

    @Test
    fun `a newly added row falls back to the entry's own chapters when the group has none unread`() = runTest {
        addedTarget(listOf(chapter(1, read = true))) { listOf(chapter(8, read = true), chapter(9)) } shouldBe 9L
    }

    /**
     * Source A leads the stitch with chapters 1 and 2, both read. Source B's copies of them, 11 and 12,
     * are unread and dropped from the group list; B's 14 is a chapter the stitch places nowhere.
     */
    private data class Copy(val id: Long, val read: Boolean)

    private val pooled = listOf(Copy(1, true), Copy(2, true), Copy(11, false), Copy(12, false), Copy(14, false))
    private val stitch = listOf(
        ChapterUnit(1L, 0, 0),
        ChapterUnit(11L, 0, 1),
        ChapterUnit(2L, 1, 0),
        ChapterUnit(12L, 1, 1),
    )

    private fun forRules(chapters: List<Copy>) = recentsChapters(
        chapters = chapters,
        pooled = pooled,
        stitch = stitch,
        id = { it.id },
        fetchedAt = { 0L },
        read = { it.read },
    )

    @Test
    fun `a newly added row's own-source fallback skips a copy the group read on another source`() = runTest {
        val groupList = forRules(pooled.take(2))

        addedTarget(groupList) { forRules(pooled.drop(2)) } shouldBe 14L
    }

    @Test
    fun `a recorded copy the stitch dropped moves on when the group read it on another source`() = runTest {
        val groupList = forRules(pooled.take(2))

        resumeTarget(groupList, recordedId = 11) { forRules(pooled.drop(2)) } shouldBe 14L
    }

    @Test
    fun `a newly added row does not fetch its own chapters while the group can answer`() = runTest {
        var fetched = false

        addedTarget(listOf(chapter(1))) { listOf(chapter(9)).also { fetched = true } }

        fetched shouldBe false
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
    fun `a recorded chapter missing from the group resumes from the entry's own source`() = runTest {
        val group = listOf(chapter(1, read = true), chapter(2))
        val ownSource = listOf(chapter(9), chapter(2))

        resumeTarget(group, recordedId = 9) { ownSource } shouldBe 9L
    }

    @Test
    fun `a recorded chapter neither list holds still opens the oldest unread`() = runTest {
        val group = listOf(chapter(1, read = true), chapter(2))

        resumeTarget(group, recordedId = 99) { emptyList() } shouldBe 2L
    }

    @Test
    fun `nothing unread anywhere resolves no target at all`() = runTest {
        val group = listOf(chapter(1, read = true))

        resumeTarget(group, recordedId = 1) { emptyList() } shouldBe null
    }

    @Test
    fun `the entry's own source is not fetched while the group can answer`() = runTest {
        var fetched = false
        val group = listOf(chapter(1, read = true), chapter(2))

        resumeTarget(group, recordedId = 2) {
            fetched = true
            emptyList()
        }

        fetched shouldBe false
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
