package reikai.domain.recommendation.taste

import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What the adult-tag pickers are offered. Ordering is the point: the pickers show the user's own
 * vocabulary, so the tags they actually read most have to come first.
 */
class GetObservedTasteTagsTest {

    private fun entry(remoteId: Long, tags: List<String>) = TrackedEntry(
        trackerId = 1L,
        remoteId = remoteId,
        title = "Title $remoteId",
        score = -1.0,
        status = TrackStatus.READING,
        tags = tags,
    )

    private fun getObservedTasteTags(vararg entries: TrackedEntry) = GetObservedTasteTags(
        object : TasteLibraryRepository {
            override suspend fun getAll() = entries.toList()
            override suspend fun replaceTracker(trackerId: Long, entries: List<TrackedEntry>, fetchedAt: Long) = Unit
            override suspend fun deleteTracker(trackerId: Long) = Unit
            override suspend fun deleteAll() = Unit
            override suspend fun count(): Long = 0
            override suspend fun lastFetch(trackerId: Long): Long? = null
        },
    )

    @Test
    fun `tags are counted across entries and ordered by how many carry them`() = runTest {
        val tags = getObservedTasteTags(
            entry(1L, listOf("action", "romance")),
            entry(2L, listOf("action", "hentai")),
            entry(3L, listOf("action")),
        ).await()

        tags shouldContainExactly listOf(
            ObservedTag("action", 3),
            ObservedTag("hentai", 1),
            ObservedTag("romance", 1),
        )
    }

    @Test
    fun `an empty cache offers nothing, which is what hides the pickers`() = runTest {
        getObservedTasteTags().await() shouldContainExactly emptyList()
    }
}
