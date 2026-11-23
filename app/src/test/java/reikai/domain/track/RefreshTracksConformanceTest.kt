package reikai.domain.track

import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.entry.EntryId
import reikai.domain.novel.interactor.RefreshNovelTracks
import reikai.domain.novel.model.NovelTrack
import reikai.util.mockIoDispatcher
import reikai.util.restubDefaultDispatcher
import tachiyomi.domain.track.model.Track
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * The track sheet's refresh, which both types run: every signed-in tracker bound in the group is
 * refreshed and stored, one failing does not stop the rest, and what failed comes back named.
 */
class RefreshTracksConformanceTest {

    enum class Type {
        MANGA {
            override fun refresh(h: Harness): suspend () -> List<Pair<Tracker?, Throwable>> {
                val interactor = RefreshTracks(
                    getTracks = mockk {
                        coEvery { groupIds(ENTRY_ID) } returns listOf(ENTRY_ID)
                        coEvery { await(ENTRY_ID) } returns h.trackerIds.map(::mangaTrack)
                    },
                    trackerManager = h.trackerManager,
                    upsertTrack = mockk {
                        coEvery { await(any()) } answers { h.stored += firstArg<Track>().lastChapterRead }
                    },
                    syncChapterProgressWithTrack = mockk(relaxed = true),
                )
                return { interactor.await(ENTRY_ID) }
            }

            override val entry = EntryId.Manga(ENTRY_ID)
        },
        NOVEL {
            override fun refresh(h: Harness): suspend () -> List<Pair<Tracker?, Throwable>> {
                val interactor = RefreshNovelTracks(
                    getNovelTracks = mockk {
                        coEvery { groupIds(ENTRY_ID) } returns listOf(ENTRY_ID)
                        coEvery { awaitGroup(ENTRY_ID) } returns h.trackerIds.map(::novelTrack)
                    },
                    trackerManager = h.trackerManager,
                    upsertNovelTrack = mockk {
                        coEvery { await(any()) } answers { h.stored += firstArg<NovelTrack>().lastChapterRead }
                    },
                )
                return { interactor.await(ENTRY_ID) }
            }

            override val entry = EntryId.Novel(ENTRY_ID)
        },
        ;

        abstract fun refresh(h: Harness): suspend () -> List<Pair<Tracker?, Throwable>>
        abstract val entry: EntryId
    }

    /** One track per tracker in [trackerIds]; [trackers] are the ones the app knows, by id. */
    class Harness(val trackerIds: List<Long>, val trackers: List<BaseTracker>) {
        val stored = mutableListOf<Double>()
        val trackerManager = mockk<TrackerManager>().also { manager ->
            every { manager.get(any()) } answers { trackers.find { it.id == firstArg<Long>() } }
        }
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a refresh stores what the tracker answered`(type: Type) = runTest {
        val h = Harness(listOf(FIRST), listOf(tracker(FIRST)))

        type.refresh(h)()

        h.stored shouldBe listOf(REMOTE_PROGRESS)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a tracker that fails comes back named`(type: Type) = runTest {
        val failing = tracker(FIRST, failure = RATE_LIMITED)
        val h = Harness(listOf(FIRST, SECOND), listOf(failing, tracker(SECOND)))

        type.refresh(h)() shouldBe listOf(failing to RATE_LIMITED)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a tracker that fails does not stop the others`(type: Type) = runTest {
        val h = Harness(listOf(FIRST, SECOND), listOf(tracker(FIRST, failure = RATE_LIMITED), tracker(SECOND)))

        type.refresh(h)()

        h.stored shouldBe listOf(REMOTE_PROGRESS)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a tracker you are signed out of is not called`(type: Type) = runTest {
        val signedOut = tracker(FIRST, loggedIn = false)
        val h = Harness(listOf(FIRST), listOf(signedOut))

        type.refresh(h)() shouldBe emptyList()

        coVerify(exactly = 0) { signedOut.refresh(any()) }
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a track bound to a tracker the app no longer has is skipped`(type: Type) = runTest {
        val h = Harness(listOf(FIRST, SECOND), listOf(tracker(SECOND)))

        type.refresh(h)() shouldBe emptyList()
    }

    /**
     * A refresh read before a push lands would store the progress the push just moved past. The manga
     * refresh reads on Dispatchers.IO, here on the test's scheduler, so a read the lock fails to hold back
     * happens before the push lands.
     */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a refresh during a chapter push stores what the push sent`(type: Type) = runTest {
        mockIoDispatcher(StandardTestDispatcher(testScheduler))
        try {
            var remote = 5.0
            val h = Harness(listOf(FIRST), listOf(tracker(FIRST, remote = { remote })))
            val refreshing = type.refresh(h)
            restubDefaultDispatcher()
            val pushLands = CompletableDeferred<Unit>()
            launch { ChapterPushLock.around(listOf(type.entry)) { pushLands.await().also { remote = 12.0 } } }
            runCurrent()

            val refresh = async { refreshing() }
            advanceUntilIdle()
            pushLands.complete(Unit)
            refresh.await()

            h.stored shouldBe listOf(12.0)
        } finally {
            unmockkStatic(Dispatchers::class)
        }
    }

    private companion object {
        const val ENTRY_ID = 7L
        const val FIRST = 3L
        const val SECOND = 4L
        const val REMOTE_PROGRESS = 20.0
        val RATE_LIMITED = HttpException(429)

        fun tracker(
            id: Long,
            loggedIn: Boolean = true,
            failure: Exception? = null,
            remote: () -> Double = { REMOTE_PROGRESS },
        ) = mockk<BaseTracker> {
            every { this@mockk.id } returns id
            every { isLoggedIn } returns loggedIn
            coEvery { refresh(any()) } answers {
                failure?.let { throw it }
                firstArg<DbTrack>().apply { last_chapter_read = remote() }
            }
        }

        fun mangaTrack(trackerId: Long) = Track(
            id = trackerId * 10,
            mangaId = ENTRY_ID,
            trackerId = trackerId,
            remoteId = 9L,
            libraryId = null,
            title = "title",
            lastChapterRead = 5.0,
            totalChapters = 0L,
            status = 0L,
            score = 0.0,
            remoteUrl = "",
            startDate = null,
            finishDate = null,
            private = false,
        )

        fun novelTrack(trackerId: Long) = NovelTrack(
            id = trackerId * 10,
            novelId = ENTRY_ID,
            trackerId = trackerId,
            remoteId = 9L,
            libraryId = null,
            title = "title",
            lastChapterRead = 5.0,
            totalChapters = 0L,
            status = 0L,
            score = 0.0,
            remoteUrl = "",
            startDate = null,
            finishDate = null,
            private = false,
        )
    }
}
