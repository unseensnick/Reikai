package reikai.domain.track

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
    fun `no tracks is no rows`() {
        canonicalTracksPerTracker(emptyList<Row>(), Row::trackerId, Row::lastChapterRead) shouldBe emptyList()
    }
}
