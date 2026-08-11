package reikai.presentation.recents

import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId

/**
 * The order a long-press sweep runs along. It is the rendered order, so a group stands in for its
 * members only while they are not drawn themselves; counting both would select a member twice and
 * make a range end somewhere the user did not sweep to.
 */
class RecentsSweepOrderTest {

    private val date = LocalDate(2026, 8, 11)

    private fun item(raw: Long, chapter: Long) = RecentsItem(
        entryId = EntryId.Manga(raw),
        timestamp = raw,
        lane = RecentsLane.Updated(ChapterRef(EntryId.Manga(raw), chapter)),
        payload = Unit,
    )

    @Test
    fun `a flat feed sweeps its rows in the order they are drawn`() {
        val first = item(raw = 1, chapter = 10)
        val second = item(raw = 2, chapter = 20)

        val refs = listOf(RecentsRow.Entry(first), RecentsRow.Entry(second)).orderedChapterRefs()

        refs shouldContainExactly listOf(first.lane.chapterRef, second.lane.chapterRef)
    }

    @Test
    fun `a collapsed group stands in for every member it hides`() {
        val members = listOf(item(raw = 1, chapter = 10), item(raw = 1, chapter = 11))

        val refs = listOf(RecentsRow.Group("g", date, members, expanded = false)).orderedChapterRefs()

        refs shouldContainExactly members.map { it.lane.chapterRef }
    }

    @Test
    fun `an expanded group leaves its members to their own rows rather than counting them twice`() {
        val members = listOf(item(raw = 1, chapter = 10), item(raw = 1, chapter = 11))
        val rows = listOf(RecentsRow.Group("g", date, members, expanded = true)) +
            members.map { RecentsRow.Child(it) }

        val refs = rows.orderedChapterRefs()

        refs shouldContainExactly members.map { it.lane.chapterRef }
    }

    @Test
    fun `a row with no chapter is not somewhere a sweep can stop`() {
        val added = RecentsItem(
            entryId = EntryId.Novel(7),
            timestamp = 1,
            lane = RecentsLane.Added,
            payload = Unit,
        )

        val refs = listOf(RecentsRow.Entry(added), RecentsRow.DateHeader(date)).orderedChapterRefs()

        refs shouldContainExactly emptyList()
    }
}
