package reikai.domain.recommendation.taste

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The cache may only hold rows for trackers the user still pulls from. Nothing else enforces it:
 * the read path takes every cached row, so a tracker whose pull was turned off would keep shaping
 * the taste profile forever.
 */
class RefreshTrackerLibraryTest {

    private class FakeFetcher(
        override val trackerId: Long,
        private val pullRequested: Boolean,
        private val loggedIn: Boolean = true,
    ) : TrackerLibraryFetcher {
        override fun isPullRequested() = pullRequested
        override fun isEnabled() = pullRequested && loggedIn
        override suspend fun fetchLibrary(): List<TrackedEntry> = emptyList()
    }

    private class RecordingRepository : TasteLibraryRepository {
        val deletedTrackers = mutableListOf<Long>()
        val replacedTrackers = mutableListOf<Long>()
        override suspend fun getAll(): List<TrackedEntry> = emptyList()
        override suspend fun replaceTracker(trackerId: Long, entries: List<TrackedEntry>, fetchedAt: Long) {
            replacedTrackers += trackerId
        }
        override suspend fun deleteTracker(trackerId: Long) {
            deletedTrackers += trackerId
        }
        override suspend fun deleteAll() = Unit
        override suspend fun count(): Long = 0
        override suspend fun lastFetch(trackerId: Long): Long? = null
    }

    @Test
    fun `a tracker the user stopped pulling from loses its cached rows`() = runTest {
        val repository = RecordingRepository()
        val fetchers = listOf(
            FakeFetcher(trackerId = 1L, pullRequested = true),
            FakeFetcher(trackerId = 2L, pullRequested = false),
        )

        RefreshTrackerLibrary(fetchers, repository).await()

        repository.deletedTrackers shouldContainExactly listOf(2L)
    }

    @Test
    fun `a tracker still being pulled from keeps its rows and is refetched`() = runTest {
        val repository = RecordingRepository()
        val fetchers = listOf(FakeFetcher(trackerId = 1L, pullRequested = true))

        RefreshTrackerLibrary(fetchers, repository).await()

        repository.deletedTrackers.shouldContainExactly(emptyList())
        repository.replacedTrackers shouldContainExactly listOf(1L)
    }

    @Test
    fun `a logged-out tracker keeps its cache, so a dropped session does not erase the profile`() = runTest {
        val repository = RecordingRepository()
        val fetchers = listOf(FakeFetcher(trackerId = 1L, pullRequested = true, loggedIn = false))

        RefreshTrackerLibrary(fetchers, repository).await()

        repository.deletedTrackers.shouldContainExactly(emptyList())
        repository.replacedTrackers.shouldContainExactly(emptyList())
    }

    @Test
    fun `the purge also runs when no tracker is left to pull from`() = runTest {
        val repository = RecordingRepository()
        val fetchers = listOf(
            FakeFetcher(trackerId = 1L, pullRequested = false),
            FakeFetcher(trackerId = 2L, pullRequested = false),
        )

        RefreshTrackerLibrary(fetchers, repository).refreshIfStale()

        repository.deletedTrackers shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }
}
