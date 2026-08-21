package reikai.presentation.recents

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType

/**
 * The recents kernel: one order and one collapse, both pure. Fixtures are built by hand because the
 * models these rows come from need the graph to stand up. Every rule has its
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

    /**
     * A surface rendering several modes collects every lane those modes need, so the assembly a
     * single-lane mode is handed carries rows it must not draw. The two-tab shape hid this: each of
     * those surfaces collects one lane, so the assembly could only ever hold what the mode wanted.
     */
    @Test
    fun `a single-lane mode draws only its own lane`() {
        val id = EntryId.Manga(1)
        val assembled = RecentsAssembled(
            items = listOf(
                manga(1, at = 30, lane = updated(id, chapterId = 300)),
                manga(2, at = 20, lane = read(EntryId.Manga(2), chapterId = 200)),
                manga(3, at = 10),
            ),
            chip = ContentType.ALL,
            loading = false,
            membership = emptyMap(),
        )

        val rows = renderRows(RecentsMode.UPDATES, assembled, groupBySeries = false, expandedGroups = emptySet())

        ids(rows.filterIsInstance<RecentsRow.Entry>().map { it.item }) shouldBe listOf(EntryId.Manga(1))
    }

    @Test
    fun `a combined mode draws every lane it collects`() {
        val id = EntryId.Manga(1)
        val assembled = RecentsAssembled(
            items = listOf(
                manga(1, at = 30, lane = updated(id, chapterId = 300)),
                manga(2, at = 20, lane = read(EntryId.Manga(2), chapterId = 200)),
                manga(3, at = 10),
            ),
            chip = ContentType.ALL,
            loading = false,
            membership = emptyMap(),
        )

        val rows = renderRows(RecentsMode.FEED, assembled, groupBySeries = false, expandedGroups = emptySet())

        ids(rows.filterIsInstance<RecentsRow.Entry>().map { it.item }) shouldBe
            listOf(EntryId.Manga(1), EntryId.Manga(2), EntryId.Manga(3))
    }

    /**
     * The keep rule runs before the collapse, so a hidden row cannot stand in for its entry: taking
     * it out afterwards would leave the entry represented by a row nobody can see, or drop the entry
     * entirely because its newest activity was the hidden one.
     */
    @Test
    fun `a row the keep rule drops is not drawn, and does not stand in for its entry`() {
        val hidden = manga(1, at = 30, lane = read(EntryId.Manga(1), chapterId = 300))
        val shown = manga(1, at = 20, lane = updated(EntryId.Manga(1), chapterId = 200))
        val assembled = RecentsAssembled(
            items = listOf(hidden, shown),
            chip = ContentType.ALL,
            loading = false,
            membership = emptyMap(),
        )

        val rows = renderRows(RecentsMode.FEED, assembled, groupBySeries = false, expandedGroups = emptySet()) {
            it != hidden
        }

        rows.filterIsInstance<RecentsRow.Entry>().map { it.item } shouldBe listOf(shown)
    }

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

    // The four render policies over that kernel. Timestamps are two days apart where a day boundary
    // matters, so no timezone can put them on one date.

    private val day1 = 1_700_000_000_000L
    private val day2 = day1 + 2 * 24 * 60 * 60 * 1000L

    private fun entries(rows: List<RecentsRow>) = rows.filterIsInstance<RecentsRow.Entry>()

    /** These entries in one merge group. Group ids are unique across both content types. */
    private fun grouped(vararg entryIds: EntryId) = entryIds.associateWith { 1L }

    private fun headers(rows: List<RecentsRow>) = rows.filterIsInstance<RecentsRow.DateHeader>()

    private fun sections(rows: List<RecentsRow>) =
        rows.filterIsInstance<RecentsRow.SectionHeader>().map { it.section }

    /** History with nothing merged, which is what most of these cases are about. */
    private fun historyRowsOf(items: List<RecentsItem>) = historyRows(items, emptyMap())

    private fun sectionRows(rows: List<RecentsRow>, section: RecentsLaneKind): Int {
        val from = rows.indexOfFirst { it is RecentsRow.SectionHeader && it.section == section }
        return rows.drop(from + 1).takeWhile { it is RecentsRow.Entry }.size
    }

    @Test
    fun `one day's rows sit under one header`() {
        val rows = historyRowsOf(listOf(manga(1, at = day1), manga(2, at = day1 + 1000)))

        headers(rows).size shouldBe 1
    }

    @Test
    fun `a new day opens a new header`() {
        val rows = historyRowsOf(listOf(manga(1, at = day2), manga(2, at = day1)))

        headers(rows).size shouldBe 2
    }

    @Test
    fun `two chapters of one series on one day collapse into a group`() {
        val id = EntryId.Manga(1)
        val items = listOf(
            manga(1, at = day1 + 1000, lane = updated(id, chapterId = 2)),
            manga(1, at = day1, lane = updated(id, chapterId = 1)),
        )

        val rows = updatesRows(items, groupBySeries = true, membership = emptyMap(), expandedKeys = emptySet())

        rows.filterIsInstance<RecentsRow.Group>().single().members.size shouldBe 2
    }

    @Test
    fun `a series' only chapter of the day stays a flat row`() {
        val items = listOf(manga(1, at = day1, lane = updated(EntryId.Manga(1), chapterId = 1)))

        val rows = updatesRows(items, groupBySeries = true, membership = emptyMap(), expandedKeys = emptySet())

        entries(rows).size shouldBe 1
    }

    @Test
    fun `grouping is per day, so yesterday's chapter is not in today's group`() {
        val id = EntryId.Manga(1)
        val items = listOf(
            manga(1, at = day2 + 1000, lane = updated(id, chapterId = 3)),
            manga(1, at = day2, lane = updated(id, chapterId = 2)),
            manga(1, at = day1, lane = updated(id, chapterId = 1)),
        )

        val rows = updatesRows(items, groupBySeries = true, membership = emptyMap(), expandedKeys = emptySet())

        rows.filterIsInstance<RecentsRow.Group>().single().members.size shouldBe 2
    }

    @Test
    fun `an expanded group is followed by its members`() {
        val id = EntryId.Manga(1)
        val items = listOf(
            manga(1, at = day1 + 1000, lane = updated(id, chapterId = 2)),
            manga(1, at = day1, lane = updated(id, chapterId = 1)),
        )
        val key = updatesRows(items, groupBySeries = true, emptyMap(), emptySet())
            .filterIsInstance<RecentsRow.Group>().single().key

        val rows = updatesRows(items, groupBySeries = true, emptyMap(), expandedKeys = setOf(key))

        rows.filterIsInstance<RecentsRow.Child>().size shouldBe 2
    }

    @Test
    fun `a collapsed group hides its members`() {
        val id = EntryId.Manga(1)
        val items = listOf(
            manga(1, at = day1 + 1000, lane = updated(id, chapterId = 2)),
            manga(1, at = day1, lane = updated(id, chapterId = 1)),
        )

        val rows = updatesRows(items, groupBySeries = true, emptyMap(), expandedKeys = emptySet())

        rows.filterIsInstance<RecentsRow.Child>().size shouldBe 0
    }

    @Test
    fun `grouping off leaves every chapter its own row`() {
        val id = EntryId.Manga(1)
        val items = listOf(
            manga(1, at = day1 + 1000, lane = updated(id, chapterId = 2)),
            manga(1, at = day1, lane = updated(id, chapterId = 1)),
        )

        val rows = updatesRows(items, groupBySeries = false, emptyMap(), emptySet())

        entries(rows).size shouldBe 2
    }

    @Test
    fun `a series merged across sources is one group`() {
        val first = EntryId.Manga(1)
        val second = EntryId.Manga(2)
        val items = listOf(
            manga(1, at = day1 + 1000, lane = updated(first, chapterId = 1)),
            manga(2, at = day1, lane = updated(second, chapterId = 2)),
        )

        val rows = updatesRows(items, groupBySeries = true, grouped(first, second), expandedKeys = emptySet())

        rows.filterIsInstance<RecentsRow.Group>().single().members.size shouldBe 2
    }

    @Test
    fun `an unmerged manga and novel sharing a row id group separately`() {
        // The reachable collision: with no group, the key falls back to the raw id, which the two
        // content types share. Group ids cannot collide, entry ids can.
        val items = listOf(
            manga(1, at = day1 + 2000, lane = updated(EntryId.Manga(1), chapterId = 1)),
            manga(1, at = day1 + 1000, lane = updated(EntryId.Manga(1), chapterId = 2)),
            novel(1, at = day1 + 500, lane = updated(EntryId.Novel(1), chapterId = 1)),
            novel(1, at = day1, lane = updated(EntryId.Novel(1), chapterId = 2)),
        )

        val rows = updatesRows(items, groupBySeries = true, emptyMap(), expandedKeys = emptySet())

        rows.filterIsInstance<RecentsRow.Group>().size shouldBe 2
    }

    @Test
    fun `two sources of one merged series are one row in the flat feed`() {
        val first = EntryId.Manga(1)
        val second = EntryId.Manga(2)
        val items = listOf(
            manga(1, at = day2, lane = read(first, chapterId = 9)),
            manga(2, at = day1, lane = updated(second, chapterId = 1)),
        )

        entries(flatRecentsRows(items, grouped(first, second))).size shouldBe 1
    }

    @Test
    fun `the surviving row of a group is its most recent activity`() {
        val first = EntryId.Manga(1)
        val second = EntryId.Manga(2)
        val items = listOf(
            manga(1, at = day1, lane = read(first, chapterId = 9)),
            manga(2, at = day2, lane = updated(second, chapterId = 1)),
        )

        entries(flatRecentsRows(items, grouped(first, second)))
            .single().item.entryId shouldBe second
    }

    @Test
    fun `an ungrouped entry survives beside a group`() {
        val first = EntryId.Manga(1)
        val second = EntryId.Manga(2)
        val loner = EntryId.Novel(1)
        val items = listOf(
            manga(1, at = day2, lane = read(first, chapterId = 9)),
            manga(2, at = day1, lane = updated(second, chapterId = 1)),
            novel(1, at = day1, lane = read(loner, chapterId = 5)),
        )

        entries(flatRecentsRows(items, grouped(first, second))).size shouldBe 2
    }

    @Test
    fun `History shows one row for a series read on two of its sources`() {
        val first = EntryId.Manga(1)
        val second = EntryId.Manga(2)
        val items = listOf(
            manga(1, at = day1 + 1000, lane = read(first, chapterId = 9)),
            manga(2, at = day1, lane = read(second, chapterId = 1)),
        )

        entries(historyRows(items, grouped(first, second))).size shouldBe 1
    }

    @Test
    fun `a merged series is one row inside a digest section`() {
        val first = EntryId.Manga(1)
        val second = EntryId.Manga(2)
        val items = listOf(
            manga(1, at = day2, lane = updated(first, chapterId = 9)),
            manga(2, at = day1, lane = updated(second, chapterId = 1)),
        )

        sectionRows(digestRows(items, grouped(first, second)), RecentsLaneKind.UPDATED) shouldBe 1
    }

    @Test
    fun `the flat feed is one row per title across lanes`() {
        val id = EntryId.Manga(1)
        val items = listOf(
            manga(1, at = day2, lane = read(id, chapterId = 9)),
            manga(1, at = day1, lane = updated(id, chapterId = 1)),
        )

        entries(flatRecentsRows(items, emptyMap())).size shouldBe 1
    }

    @Test
    fun `the flat feed carries no date headers`() {
        val rows = flatRecentsRows(listOf(manga(1, at = day1), manga(2, at = day2)), emptyMap())

        headers(rows).size shouldBe 0
    }

    @Test
    fun `a series read and updated today takes one digest row, not one per section`() {
        val id = EntryId.Manga(1)
        val items = listOf(
            manga(1, at = day2, lane = read(id, chapterId = 9)),
            manga(1, at = day1, lane = updated(id, chapterId = 1)),
        )

        entries(digestRows(items, emptyMap())).size shouldBe 1
    }

    @Test
    fun `the section a series lands in is the one holding its newest activity`() {
        val id = EntryId.Manga(1)
        val items = listOf(
            manga(1, at = day1, lane = read(id, chapterId = 9)),
            manga(1, at = day2, lane = updated(id, chapterId = 1)),
        )

        sections(digestRows(items, emptyMap())) shouldBe listOf(RecentsLaneKind.UPDATED)
    }

    /** The collapse is per merge group, so two sources of one series cannot each claim a section. */
    @Test
    fun `a merged series read on one source and updated on the other takes one digest row`() {
        val first = EntryId.Manga(1)
        val second = EntryId.Manga(2)
        val items = listOf(
            manga(1, at = day2, lane = read(first, chapterId = 9)),
            manga(2, at = day1, lane = updated(second, chapterId = 1)),
        )

        entries(digestRows(items, grouped(first, second))).size shouldBe 1
    }

    @Test
    fun `new chapters cap at four`() {
        val items = (1..6).map { manga(it.toLong(), at = day1 + it, lane = updated(EntryId.Manga(it.toLong()), 1)) }

        sectionRows(digestRows(items, emptyMap()), RecentsLaneKind.UPDATED) shouldBe 4
    }

    @Test
    fun `newly added caps at four`() {
        val items = (1..6).map { manga(it.toLong(), at = day1 + it) }

        sectionRows(digestRows(items, emptyMap()), RecentsLaneKind.ADDED) shouldBe 4
    }

    @Test
    fun `continue reading takes what new chapters left of the nine`() {
        val updates = (1..4).map { manga(it.toLong(), at = day1 + it, lane = updated(EntryId.Manga(it.toLong()), 1)) }
        val reads = (10..20).map { novel(it.toLong(), at = day1 + it, lane = read(EntryId.Novel(it.toLong()), 1)) }

        sectionRows(digestRows(updates + reads, emptyMap()), RecentsLaneKind.READ) shouldBe 5
    }

    @Test
    fun `sections are ordered by their newest row`() {
        val items = listOf(
            manga(1, at = day1, lane = updated(EntryId.Manga(1), chapterId = 1)),
            novel(1, at = day2, lane = read(EntryId.Novel(1), chapterId = 1)),
        )

        sections(digestRows(items, emptyMap())) shouldBe listOf(RecentsLaneKind.READ, RecentsLaneKind.UPDATED)
    }

    @Test
    fun `an empty section emits no header`() {
        val items = listOf(manga(1, at = day1, lane = updated(EntryId.Manga(1), chapterId = 1)))

        sections(digestRows(items, emptyMap())) shouldBe listOf(RecentsLaneKind.UPDATED)
    }

    @Test
    fun `a chapter section offers a way into its own mode`() {
        val items = listOf(manga(1, at = day1, lane = updated(EntryId.Manga(1), chapterId = 1)))

        digestRows(items, emptyMap()).filterIsInstance<RecentsRow.SectionFooter>().size shouldBe 1
    }

    @Test
    fun `newly added has no mode to jump to, so it carries no footer`() {
        val items = listOf(manga(1, at = day1))

        digestRows(items, emptyMap()).filterIsInstance<RecentsRow.SectionFooter>().size shouldBe 0
    }
}
