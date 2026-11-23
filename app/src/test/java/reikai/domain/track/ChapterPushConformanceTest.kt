package reikai.domain.track

import eu.kanade.domain.track.interactor.TrackChapter
import eu.kanade.domain.track.store.DelayedTrackingStore
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.track.NovelDelayedTrackingStore
import reikai.domain.novel.track.TrackNovelChapter
import tachiyomi.domain.track.model.Track
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * Both chapter interactors report the trackers a push failed at, which the details mark-read toast
 * reads, a refused push still queues its retry, and a landed one clears it. One case per rule over both types.
 */
class ChapterPushConformanceTest {

    enum class Type {
        MANGA {
            override fun push(h: Harness): suspend () -> ChapterPushOutcome {
                val interactor = TrackChapter(
                    getTracks = mockk {
                        coEvery { groupIds(any()) } returns listOf(ENTRY_ID)
                        coEvery { await(ENTRY_ID) } returns listOf(mangaTrack())
                    },
                    trackerManager = h.trackerManager,
                    upsertTrack = mockk(relaxed = true),
                    delayedTrackingStore = h.mangaStore,
                )
                return { interactor.await(mockk(), ENTRY_ID, CHAPTER, setupJobOnFailure = false) }
            }

            override fun retry(h: Harness): suspend () -> ChapterPushOutcome {
                val interactor = TrackChapter(
                    getTracks = mockk {
                        coEvery { groupIds(any()) } returns listOf(ENTRY_ID)

                        coEvery { await(ENTRY_ID) } returns listOf(mangaTrack(), mangaTrack().copy(id = OTHER_TRACK_ID))
                    },
                    trackerManager = h.trackerManager,
                    upsertTrack = mockk(relaxed = true),
                    delayedTrackingStore = h.mangaStore,
                )
                return { interactor.retry(mockk(), mangaTrack(), CHAPTER) }
            }

            override fun savingPush(h: Harness): suspend () -> ChapterPushOutcome {
                val interactor = TrackChapter(
                    getTracks = mockk {
                        coEvery { groupIds(any()) } returns listOf(ENTRY_ID)

                        coEvery { await(ENTRY_ID) } answers { listOf(mangaTrack().copy(lastChapterRead = h.saved)) }
                    },
                    trackerManager = h.trackerManager,
                    upsertTrack = mockk {
                        coEvery { await(any()) } answers
                            { h.saved = firstArg<Track>().lastChapterRead }
                    },
                    delayedTrackingStore = h.mangaStore,
                )
                return { interactor.await(mockk(), ENTRY_ID, CHAPTER, setupJobOnFailure = false) }
            }

            override fun siblingRace(h: Harness): List<suspend () -> ChapterPushOutcome> {
                val sibling = mangaTrack().copy(mangaId = SIBLING_ID)
                val interactor = TrackChapter(
                    getTracks = mockk {
                        coEvery { groupIds(any()) } returns listOf(ENTRY_ID, SIBLING_ID)
                        coEvery { await(ENTRY_ID) } returns listOf(sibling)
                    },
                    trackerManager = h.trackerManager,
                    upsertTrack = mockk(relaxed = true),
                    delayedTrackingStore = h.mangaStore,
                )
                return listOf(
                    { interactor.await(mockk(), ENTRY_ID, CHAPTER, setupJobOnFailure = false) },
                    { interactor.retry(mockk(), sibling, CHAPTER) },
                )
            }

            override fun store(h: Harness): DelayedTrackingStore = h.mangaStore
        },
        NOVEL {
            override fun push(h: Harness): suspend () -> ChapterPushOutcome {
                val interactor = TrackNovelChapter(
                    getNovelTracks = mockk {
                        coEvery { groupIds(any()) } returns listOf(ENTRY_ID)
                        coEvery { awaitGroup(ENTRY_ID) } returns listOf(novelTrack())
                    },
                    trackerManager = h.trackerManager,
                    upsertNovelTrack = mockk(relaxed = true),
                    delayedTrackingStore = h.novelStore,
                )
                return { interactor.await(mockk(), ENTRY_ID, CHAPTER, setupJobOnFailure = false) }
            }

            override fun retry(h: Harness): suspend () -> ChapterPushOutcome {
                val interactor = TrackNovelChapter(
                    getNovelTracks = mockk {
                        coEvery { groupIds(any()) } returns listOf(ENTRY_ID)

                        coEvery { awaitGroup(ENTRY_ID) } returns
                            listOf(novelTrack(), novelTrack().copy(id = OTHER_TRACK_ID))
                    },
                    trackerManager = h.trackerManager,
                    upsertNovelTrack = mockk(relaxed = true),
                    delayedTrackingStore = h.novelStore,
                )
                return { interactor.retry(mockk(), novelTrack(), CHAPTER) }
            }

            override fun savingPush(h: Harness): suspend () -> ChapterPushOutcome {
                val interactor = TrackNovelChapter(
                    getNovelTracks = mockk {
                        coEvery { groupIds(any()) } returns listOf(ENTRY_ID)

                        coEvery { awaitGroup(ENTRY_ID) } answers
                            { listOf(novelTrack().copy(lastChapterRead = h.saved)) }
                    },
                    trackerManager = h.trackerManager,
                    upsertNovelTrack = mockk {
                        coEvery { await(any()) } answers { h.saved = firstArg<NovelTrack>().lastChapterRead }
                    },
                    delayedTrackingStore = h.novelStore,
                )
                return { interactor.await(mockk(), ENTRY_ID, CHAPTER, setupJobOnFailure = false) }
            }

            override fun siblingRace(h: Harness): List<suspend () -> ChapterPushOutcome> {
                val sibling = novelTrack().copy(novelId = SIBLING_ID)
                val interactor = TrackNovelChapter(
                    getNovelTracks = mockk {
                        coEvery { groupIds(any()) } returns listOf(ENTRY_ID, SIBLING_ID)
                        coEvery { awaitGroup(ENTRY_ID) } returns listOf(sibling)
                    },
                    trackerManager = h.trackerManager,
                    upsertNovelTrack = mockk(relaxed = true),
                    delayedTrackingStore = h.novelStore,
                )
                return listOf(
                    { interactor.await(mockk(), ENTRY_ID, CHAPTER, setupJobOnFailure = false) },
                    { interactor.retry(mockk(), sibling, CHAPTER) },
                )
            }

            override fun store(h: Harness): DelayedTrackingStore = h.novelStore
        },
        ;

        abstract fun push(h: Harness): suspend () -> ChapterPushOutcome

        /** Retries the queued [TRACK_ID] while another row of its group, [OTHER_TRACK_ID], is behind too. */
        abstract fun retry(h: Harness): suspend () -> ChapterPushOutcome
        abstract fun store(h: Harness): DelayedTrackingStore

        /** A push over a row that keeps what was saved, as the database does. */
        abstract fun savingPush(h: Harness): suspend () -> ChapterPushOutcome

        /** A reader push on one copy of a merged series and a queued retry of the sibling's row it reads. */
        abstract fun siblingRace(h: Harness): List<suspend () -> ChapterPushOutcome>
    }

    class Harness(failure: Exception?, slow: Boolean = false) {
        val pushedTrackIds = mutableListOf<Long>()
        var saved = 5.0
        var remote = 5.0
        val tracker = mockk<BaseTracker> {
            every { id } returns TRACKER_ID
            every { isLoggedIn } returns true
            every { supportsReadingDates } returns false
            coEvery { refresh(any()) } answers { firstArg<DbTrack>().apply { last_chapter_read = remote } }
            coEvery { update(any(), any()) } coAnswers {
                if (slow) delay(1_000)
                failure?.let { throw it }
                firstArg<DbTrack>().also {
                    pushedTrackIds += it.id!!
                    remote = it.last_chapter_read
                }
            }
        }
        val trackerManager = mockk<TrackerManager> { every { get(TRACKER_ID) } returns tracker }
        val mangaStore = mockk<DelayedTrackingStore>(relaxed = true)
        val novelStore = mockk<NovelDelayedTrackingStore>(relaxed = true)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a push the tracker refuses comes back failed`(type: Type) = runTest {
        val h = Harness(RATE_LIMITED)

        type.push(h)() shouldBe ChapterPushOutcome(failed = listOf(h.tracker to RATE_LIMITED))
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a push the tracker refuses is still queued for retry`(type: Type) = runTest {
        val h = Harness(RATE_LIMITED)

        type.push(h)()

        verify { type.store(h).add(TRACK_ID, CHAPTER) }
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a push that lands comes back with no failure`(type: Type) = runTest {
        val h = Harness(failure = null)

        type.push(h)() shouldBe ChapterPushOutcome(failed = emptyList())
    }

    /** The delayed-tracking drain never removes an item itself, so a landed push must, or it retries forever. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a push that lands clears its queued retry`(type: Type) = runTest {
        val h = Harness(failure = null)

        type.push(h)()

        verify { type.store(h).remove(TRACK_ID) }
    }

    /**
     * The queue keeps only a row and the number read, not which source of a merged series it was read
     * on, so another row cannot be judged against that number: a server tracker on another source would
     * take a number from the wrong source's numbering.
     */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a queued retry pushes only the row it queued`(type: Type) = runTest {
        val h = Harness(failure = null)

        type.retry(h)()

        h.pushedTrackIds shouldBe listOf(TRACK_ID)
    }

    /** The last page reached twice before the first push lands: both read the old progress without the lock. */
    @ParameterizedTest
    @EnumSource(Type::class)
    fun `two pushes of one chapter at once reach the tracker once`(type: Type) = runTest {
        val h = Harness(failure = null, slow = true)
        val push = type.savingPush(h)

        listOf(async { push() }, async { push() }).awaitAll()

        h.pushedTrackIds shouldBe listOf(TRACK_ID)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a reader push and a sibling's queued retry at once reach the tracker once`(type: Type) = runTest {
        val h = Harness(failure = null, slow = true)

        type.siblingRace(h).map { async { it() } }.awaitAll()

        h.pushedTrackIds shouldBe listOf(TRACK_ID)
    }

    private companion object {
        const val ENTRY_ID = 7L
        const val SIBLING_ID = 8L
        const val TRACK_ID = 1L
        const val OTHER_TRACK_ID = 2L
        const val TRACKER_ID = 3L
        const val CHAPTER = 12.0
        val RATE_LIMITED = HttpException(429)

        fun mangaTrack() = Track(
            id = TRACK_ID,
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

        fun novelTrack() = NovelTrack(
            id = TRACK_ID,
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
