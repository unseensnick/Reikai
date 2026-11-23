package reikai.domain.track

import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.interactor.UpsertNovelTrack
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.track.TrackNovelChapter
import tachiyomi.domain.track.interactor.UpsertTrack
import tachiyomi.domain.track.model.Track
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * A chapter push never moves a service backwards, for both content types. The local row can be stale
 * (read on another device), so the rule is judged against the progress the refresh just read from the
 * service, not the row the push started from.
 */
class ForwardOnlyPushConformanceTest {

    enum class Type {
        MANGA {
            override suspend fun push(h: Harness) {
                TrackChapter(
                    getTracks = mockk {
                        coEvery { groupIds(any()) } answers { listOf(firstArg()) }
                        coEvery { await(ENTRY_ID) } returns listOf(mangaTrack())
                    },
                    trackerManager = h.trackerManager,
                    upsertTrack = mockk<UpsertTrack> {
                        coEvery { await(any()) } answers { h.stored += firstArg<Track>().lastChapterRead }
                    },
                    delayedTrackingStore = mockk(relaxed = true),
                ).await(mockk(), ENTRY_ID, CHAPTER, setupJobOnFailure = false)
            }
        },
        NOVEL {
            override suspend fun push(h: Harness) {
                TrackNovelChapter(
                    getNovelTracks = mockk {
                        coEvery { groupIds(any()) } answers { listOf(firstArg()) }
                        coEvery { awaitGroup(ENTRY_ID) } returns listOf(novelTrack())
                    },
                    trackerManager = h.trackerManager,
                    upsertNovelTrack = mockk<UpsertNovelTrack> {
                        coEvery { await(any()) } answers { h.stored += firstArg<NovelTrack>().lastChapterRead }
                    },
                    delayedTrackingStore = mockk(relaxed = true),
                ).await(mockk(), ENTRY_ID, CHAPTER, setupJobOnFailure = false)
            }
        },
        ;

        abstract suspend fun push(h: Harness)
    }

    /** A service at [onService], whatever the local row says, recording each chapter it is sent. */
    class Harness(onService: Double) {
        val sent = mutableListOf<Double>()
        val stored = mutableListOf<Double>()
        private val tracker = mockk<BaseTracker> {
            every { id } returns TRACKER_ID
            every { isLoggedIn } returns true
            every { supportsReadingDates } returns false
            coEvery { refresh(any()) } answers { firstArg<DbTrack>().apply { last_chapter_read = onService } }
            coEvery { update(any(), any()) } answers { firstArg<DbTrack>().also { sent += it.last_chapter_read } }
        }
        val trackerManager = mockk<TrackerManager> { every { get(TRACKER_ID) } returns tracker }
    }

    @ParameterizedTest
    @MethodSource("positions")
    fun `a push reaches the service only when the chapter is past the service's progress`(
        type: Type,
        onService: Double,
        sent: List<Double>,
    ) = runTest {
        val h = Harness(onService)

        type.push(h)

        h.sent shouldBe sent
    }

    /** A skipped push still stores what the refresh read, so the local row catches up with the service. */
    @ParameterizedTest
    @MethodSource("servicePositions")
    fun `the row stored is the further of the chapter and the service's progress`(
        type: Type,
        onService: Double,
    ) = runTest {
        val h = Harness(onService)

        type.push(h)

        h.stored shouldBe listOf(maxOf(onService, CHAPTER))
    }

    companion object {
        private const val ENTRY_ID = 7L
        private const val TRACKER_ID = 3L

        /** Read here; the local row says 5, so every case passes the stale-row check. */
        private const val CHAPTER = 12.0

        @JvmStatic
        fun positions() = Type.entries.flatMap { type ->
            listOf(
                Arguments.of(type, 20.0, emptyList<Double>()), // ahead: read on another device
                Arguments.of(type, CHAPTER, emptyList<Double>()), // equal
                Arguments.of(type, 8.0, listOf(CHAPTER)), // behind
            )
        }

        @JvmStatic
        fun servicePositions() = positions().map { Arguments.of(it.get()[0], it.get()[1]) }

        private fun mangaTrack() = Track(
            id = 1L,
            mangaId = ENTRY_ID,
            trackerId = TRACKER_ID,
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

        private fun novelTrack() = NovelTrack(
            id = 1L,
            novelId = ENTRY_ID,
            trackerId = TRACKER_ID,
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
