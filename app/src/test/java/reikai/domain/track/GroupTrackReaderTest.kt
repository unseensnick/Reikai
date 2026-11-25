package reikai.domain.track

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference

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
            sharing = Switch(false),
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
            sharing = Switch(true),
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

        val emissions = reader.subscribe(1L).take(2).toList()

        emissions.first().map { it.trackerId } shouldContainExactly listOf(10L)
        emissions.last().map { it.trackerId } shouldContainExactly listOf(10L, 20L)
    }

    @Test
    fun `an open subscription follows the sharing switch`() = runTest {
        val tracks = mapOf(1L to listOf(Row(1L, 10L, 0.0)), 2L to listOf(Row(2L, 20L, 0.0)))
        val sharing = Switch(true)
        val reader = GroupTrackReader(
            sharing = sharing,
            relatedIds = { listOf(1L, 2L) },
            groupChanges = flowOf(Unit),
            readOne = { emptyList() },
            observeOne = { flowOf(tracks.getValue(it)) },
            trackerId = Row::trackerId,
            lastChapterRead = Row::lastChapterRead,
        )
        val emissions = mutableListOf<List<Long>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            reader.subscribe(1L).collect { rows -> emissions += rows.map { it.trackerId } }
        }

        sharing.set(false)

        emissions.last() shouldContainExactly listOf(10L)
    }

    /** The sharing switch, emitting the value it holds and each one set after. */
    private class Switch(initial: Boolean) : Preference<Boolean> {
        private val value = MutableStateFlow(initial)
        override fun key() = "sync_tracker_links_grouped"
        override fun get() = value.value
        override fun set(value: Boolean) {
            this.value.value = value
        }
        override fun isSet() = true
        override fun delete() = Unit
        override fun defaultValue() = true
        override fun changes(): Flow<Boolean> = value
        override fun stateIn(scope: CoroutineScope): StateFlow<Boolean> = value
    }

    @Test
    fun `no tracks is no rows`() {
        canonicalTracksPerTracker(emptyList<Row>(), Row::trackerId, Row::lastChapterRead) shouldBe emptyList()
    }
}
