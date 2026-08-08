package reikai.presentation.recents

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId

/**
 * The recents kernel: one order and one collapse, both pure. Fixtures are built by hand because the
 * models these rows come from resolve Injekt at construction and cannot run here. Every rule has its
 * twin across content types, since the two engines are separate and this is where they would drift.
 */
class RecentsAssemblyTest {

    private fun manga(id: Long, at: Long, lane: RecentsLane = RecentsLane.Added) =
        RecentsItem(EntryId.Manga(id), at, lane, payload = "m$id@$at")

    private fun novel(id: Long, at: Long, lane: RecentsLane = RecentsLane.Added) =
        RecentsItem(EntryId.Novel(id), at, lane, payload = "n$id@$at")

    private fun updated(entryId: EntryId, chapterId: Long) =
        RecentsLane.Updated(ChapterRef(entryId, chapterId))

    private fun read(entryId: EntryId, chapterId: Long) =
        RecentsLane.Read(ChapterRef(entryId, chapterId))

    private fun ids(items: List<RecentsItem>) = items.map { it.entryId }

    @Test
    fun `rows are ordered newest first`() {
        val items = listOf(manga(1, at = 10), manga(2, at = 30), manga(3, at = 20))

        ids(orderRecents(items)) shouldBe listOf(EntryId.Manga(2), EntryId.Manga(3), EntryId.Manga(1))
    }

    @Test
    fun `a manga update burst collapses to its newest chapter`() {
        val id = EntryId.Manga(1)
        val items = listOf(
            manga(1, at = 10, lane = updated(id, chapterId = 100)),
            manga(1, at = 30, lane = updated(id, chapterId = 300)),
            manga(1, at = 20, lane = updated(id, chapterId = 200)),
        )

        val collapsed = collapseByEntry(items)

        collapsed.size shouldBe 1
        (collapsed.single().lane as RecentsLane.Updated).chapter.chapterId shouldBe 300L
    }

    @Test
    fun `a novel update burst collapses to its newest chapter`() {
        val id = EntryId.Novel(1)
        val items = listOf(
            novel(1, at = 10, lane = updated(id, chapterId = 100)),
            novel(1, at = 30, lane = updated(id, chapterId = 300)),
            novel(1, at = 20, lane = updated(id, chapterId = 200)),
        )

        val collapsed = collapseByEntry(items)

        collapsed.size shouldBe 1
        (collapsed.single().lane as RecentsLane.Updated).chapter.chapterId shouldBe 300L
    }

    @Test
    fun `the survivor across lanes is the most recent activity, whichever lane that was`() {
        val id = EntryId.Manga(1)
        val readLater = listOf(
            manga(1, at = 10, lane = updated(id, chapterId = 100)),
            manga(1, at = 30, lane = read(id, chapterId = 900)),
        )
        val updatedLater = listOf(
            manga(1, at = 30, lane = updated(id, chapterId = 100)),
            manga(1, at = 10, lane = read(id, chapterId = 900)),
        )

        collapseByEntry(readLater).single().lane shouldBe read(id, chapterId = 900)
        collapseByEntry(updatedLater).single().lane shouldBe updated(id, chapterId = 100)
    }

    @Test
    fun `a manga and a novel sharing a row id stay two rows`() {
        val items = listOf(manga(7, at = 10), novel(7, at = 20))

        collapseByEntry(items).size shouldBe 2
    }

    @Test
    fun `a manga and a novel sharing a chapter row id stay two rows`() {
        val items = listOf(
            manga(1, at = 10, lane = updated(EntryId.Manga(1), chapterId = 70)),
            novel(2, at = 20, lane = updated(EntryId.Novel(2), chapterId = 70)),
        )

        collapseByEntry(items).size shouldBe 2
    }

    @Test
    fun `at the same instant a read leads an update, which leads an addition`() {
        // The ids deliberately run against the expected order: sorted by id alone these come out 1, 2,
        // 3, so only the lane tiebreak can produce 3, 2, 1.
        val items = listOf(
            manga(1, at = 50),
            manga(2, at = 50, lane = updated(EntryId.Manga(2), chapterId = 1)),
            manga(3, at = 50, lane = read(EntryId.Manga(3), chapterId = 1)),
        )

        ids(orderRecents(items)) shouldBe listOf(EntryId.Manga(3), EntryId.Manga(2), EntryId.Manga(1))
    }

    @Test
    fun `the order does not depend on which order the lanes arrived in`() {
        // The one rule a stable sort would silently satisfy in whatever order the input happened to
        // have: rows that tie on timestamp AND lane must still land the same way every emission.
        val items = listOf(manga(2, at = 50), novel(1, at = 50), manga(1, at = 50), novel(2, at = 50))

        val forwards = ids(orderRecents(items))
        val backwards = ids(orderRecents(items.reversed()))

        forwards shouldBe backwards
        forwards shouldBe listOf(
            EntryId.Manga(1),
            EntryId.Manga(2),
            EntryId.Novel(1),
            EntryId.Novel(2),
        )
    }

    @Test
    fun `collapsing keeps every entry that appears only once`() {
        val items = listOf(manga(1, at = 30), novel(1, at = 20), manga(2, at = 10))

        ids(collapseByEntry(items)) shouldBe listOf(EntryId.Manga(1), EntryId.Novel(1), EntryId.Manga(2))
    }

    @Test
    fun `collapsing within one lane leaves the other lanes untouched`() {
        // What lets the digest show a series under both new chapters and continue reading: the caller
        // chooses the scope, so collapsing a lane cannot reach into another one.
        val id = EntryId.Manga(1)
        val updatedLane = listOf(
            manga(1, at = 30, lane = updated(id, chapterId = 300)),
            manga(1, at = 10, lane = updated(id, chapterId = 100)),
        )
        val readLane = listOf(manga(1, at = 20, lane = read(id, chapterId = 900)))

        collapseByEntry(updatedLane).size shouldBe 1
        collapseByEntry(readLane).size shouldBe 1
    }
}
