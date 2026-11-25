package reikai.domain.track

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class GroupTrackReaderTest {

    private data class Row(val member: Long, val trackerId: Long, val lastChapterRead: Double)

    private fun canonical(vararg rows: Row) =
        canonicalTracksPerTracker(rows.toList(), Row::trackerId, Row::lastChapterRead)

    @Test
    fun `keeps the furthest-read row of a tracker`() {
        canonical(
            Row(member = 1L, trackerId = 10L, lastChapterRead = 5.0),
            Row(member = 2L, trackerId = 10L, lastChapterRead = 21.0),
        ).map { it.member } shouldContainExactly listOf(2L)
    }

    @Test
    fun `keeps one row per tracker, in first-seen tracker order`() {
        canonical(
            Row(member = 1L, trackerId = 10L, lastChapterRead = 5.0),
            Row(member = 1L, trackerId = 20L, lastChapterRead = 3.0),
            Row(member = 2L, trackerId = 10L, lastChapterRead = 21.0),
        ).map { it.trackerId to it.member } shouldContainExactly listOf(10L to 2L, 20L to 1L)
    }

    @Test
    fun `a tie keeps the earlier member`() {
        canonical(
            Row(member = 1L, trackerId = 10L, lastChapterRead = 7.0),
            Row(member = 2L, trackerId = 10L, lastChapterRead = 7.0),
        ).map { it.member } shouldContainExactly listOf(1L)
    }

    @Test
    fun `the span is the entry alone when sharing is off`() = runTest {
        val reader = GroupTrackReader<Row>(
            sharingEnabled = { false },
            relatedIds = { listOf(it, 2L) },
            groupChanges = emptyFlow<Unit>(),
            readOne = { emptyList() },
            observeOne = { emptyFlow() },
            trackerId = Row::trackerId,
            lastChapterRead = Row::lastChapterRead,
        )

        reader.groupIds(1L) shouldBe listOf(1L)
    }

    @Test
    fun `subscribe emits the entry's own tracks before the group resolves`() = runTest {
        val tracks = mapOf(1L to listOf(Row(1L, 10L, 0.0)), 2L to listOf(Row(2L, 20L, 0.0)))
        val reader = GroupTrackReader(
            sharingEnabled = { true },
            // The membership lookup hits the database, which is why the first emission skips it.
            relatedIds = {
                delay(10)
                listOf(1L, 2L)
            },
            groupChanges = flowOf(Unit),
            readOne = { emptyList() },
            observeOne = { flowOf(tracks.getValue(it)) },
            trackerId = Row::trackerId,
            lastChapterRead = Row::lastChapterRead,
        )

        val emissions = reader.subscribe(1L).toList()

        emissions.first().map { it.trackerId } shouldContainExactly listOf(10L)
        emissions.last().map { it.trackerId } shouldContainExactly listOf(10L, 20L)
    }

    @Test
    fun `no tracks is no rows`() {
        canonicalTracksPerTracker(emptyList<Row>(), Row::trackerId, Row::lastChapterRead) shouldBe emptyList()
    }
}
