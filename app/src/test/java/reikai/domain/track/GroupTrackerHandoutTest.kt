package reikai.domain.track

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The pre-split tracker hand-out both content types call, over a stand-in track row. */
class GroupTrackerHandoutTest {

    private data class Row(val entryId: Long, val trackerId: Long, val remoteId: Long, val lastRead: Double = 0.0)

    private val written = mutableListOf<List<Row>>()

    private suspend fun handOut(
        tracks: Map<Long, List<Row>>,
        unfavorited: Set<Long> = emptySet(),
        enabled: Boolean = true,
    ) = handOutGroupTrackers(
        enabled = enabled,
        groupIds = tracks.keys.toList(),
        isFavorite = { it !in unfavorited },
        tracksOf = { tracks.getValue(it) },
        trackerId = Row::trackerId,
        remoteId = Row::remoteId,
        lastChapterRead = Row::lastRead,
        copyTo = { row, entryId -> row.copy(entryId = entryId) },
        writeAll = { written += it },
    )

    @Test
    fun `mirrors a tracker onto every other favorited member`() = runTest {
        handOut(mapOf(1L to listOf(Row(1L, 10L, 100L)), 2L to emptyList(), 3L to emptyList()))

        written.flatten().map { it.entryId to it.trackerId } shouldContainExactlyInAnyOrder
            listOf(2L to 10L, 3L to 10L)
    }

    @Test
    fun `does nothing when the setting is off`() = runTest {
        handOut(mapOf(1L to listOf(Row(1L, 10L, 100L)), 2L to emptyList()), enabled = false)

        written shouldBe emptyList()
    }

    @Test
    fun `skips a tracker whose remote id conflicts across members`() = runTest {
        handOut(mapOf(1L to listOf(Row(1L, 10L, 100L)), 2L to listOf(Row(2L, 10L, 999L))))

        written shouldBe emptyList()
    }

    @Test
    fun `does not link a tracker onto an unfavorited member`() = runTest {
        handOut(
            mapOf(1L to listOf(Row(1L, 10L, 100L)), 2L to emptyList(), 3L to emptyList()),
            unfavorited = setOf(3L),
        )

        written.flatten().map { it.entryId } shouldContainExactlyInAnyOrder listOf(2L)
    }

    @Test
    fun `does not re-insert a tracker a member already has at the same progress`() = runTest {
        handOut(mapOf(1L to listOf(Row(1L, 10L, 100L)), 2L to listOf(Row(2L, 10L, 100L))))

        written shouldBe emptyList()
    }

    @Test
    fun `levels a member up to the group's furthest-read row in one batch`() = runTest {
        handOut(mapOf(1L to listOf(Row(1L, 10L, 100L, 5.0)), 2L to listOf(Row(2L, 10L, 100L, 21.0))))

        written.map { batch -> batch.map { it.entryId to it.lastRead } } shouldBe listOf(listOf(1L to 21.0))
    }
}
