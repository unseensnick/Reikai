package reikai.presentation.reader.text

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import reikai.domain.merge.ChapterUnit
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.interactor.DeleteNovelChaptersAfterRead
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.track.NovelDelayedTrackingStore
import reikai.domain.novel.track.TrackNovelChapter
import reikai.domain.reader.ChapterIncognito
import reikai.domain.track.TrackedChapterNumber
import reikai.domain.track.ownChapterNumbers
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences
import kotlin.time.Instant

/**
 * What finishing a chapter marks read in every novel reader. Novel 1 lists chapter 5 twice (5 and 55);
 * novel 2, merged with it, holds the stitched copy of it (205, numbered 4 on its own list) and a
 * chapter 5 of its own (206) that is a different chapter.
 */
class NovelChapterFinishTest {

    private fun chapter(id: Long, novelId: Long, number: Double) = NovelChapter(
        id = id, novelId = novelId, url = "/$id", name = "Chapter $number", read = false, bookmark = false,
        lastTextProgress = 0L, chapterNumber = number, sourceOrder = id, dateFetch = Instant.fromEpochMilliseconds(0L),
        dateUpload = null, page = "",
    )

    private val finished = chapter(5L, novelId = 1L, number = 5.0)
    private val stitch = listOf(ChapterUnit(5L, 0, 0), ChapterUnit(205L, 0, 1), ChapterUnit(206L, 1, 1))
    private val repo = mockk<NovelChapterRepository>(relaxed = true).also {
        coEvery { it.getByNovelId(1L) } returns listOf(finished, chapter(55L, novelId = 1L, number = 5.0))
        coEvery { it.getByNovelId(2L) } returns
            listOf(chapter(205L, novelId = 2L, number = 4.0), chapter(206L, novelId = 2L, number = 5.0))
    }

    private val trackNovelChapter = mockk<TrackNovelChapter>(relaxed = true)
    private val deleteAfterRead = mockk<DeleteNovelChaptersAfterRead>(relaxed = true)

    private fun TestScope.subject(
        libraryStore: InMemoryPreferenceStore = InMemoryPreferenceStore(),
        trackStore: InMemoryPreferenceStore = InMemoryPreferenceStore(),
        trackNovelChapter: TrackNovelChapter = this@NovelChapterFinishTest.trackNovelChapter,
        trackedChapterNumber: TrackedChapterNumber = ownChapterNumbers(),
    ) = NovelChapterFinish(
        chapterRepo = repo,
        setNovelReadStatus = SetNovelReadStatus(
            repo,
            deleteAfterRead,
            mockk(relaxed = true),
            mockk(relaxed = true),
        ),
        libraryPreferences = LibraryPreferences(libraryStore),
        trackPreferences = TrackPreferences(trackStore),
        trackNovelChapter = trackNovelChapter,
        trackedChapterNumber = trackedChapterNumber,
        incognito = ChapterIncognito(sourceOf = { null }, isIncognito = { false }),
        scope = backgroundScope,
        context = mockk(relaxed = true),
    )

    private suspend fun NovelChapterFinish.markedRead(): Set<Long> {
        finish(finished, memberIds = listOf(1L, 2L), stitch = stitch) {}
        val ids = slot<List<Long>>()
        coVerify { repo.setReadBulk(capture(ids), true) }
        return ids.captured.toSet()
    }

    @Test
    fun `finishing a chapter marks its second listing and its stitched copy, not a sibling's same number`() =
        runTest {
            subject().markedRead() shouldBe setOf(5L, 55L, 205L)
        }

    @Test
    fun `with mark duplicate read off, finishing marks only the chapter`() = runTest {
        val off = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    "mark_duplicate_read_chapter_read",
                    emptySet<String>(),
                    emptySet(),
                ),
            ),
        )

        subject(off).markedRead() shouldBe setOf(5L)
    }

    @Test
    fun `a chapter finished again in the same session is not marked again`() = runTest {
        val finish = subject()

        finish.finish(finished, memberIds = listOf(1L, 2L), stitch = stitch) {}
        finish.finish(finished, memberIds = listOf(1L, 2L), stitch = stitch) {}

        coVerify(exactly = 1) { repo.setReadBulk(listOf(5L, 55L, 205L), true) }
    }

    @Test
    fun `finishing a chapter pushes it to the trackers when auto update is on`() = runTest {
        subject().finish(finished, memberIds = listOf(1L, 2L), stitch = stitch) {}
        runCurrent()

        coVerify { trackNovelChapter.await(any(), 1L, 5.0) }
    }

    /**
     * A source chip and a History resume both finish novel 2's own copy (205, numbered 4 there) of
     * novel 1's chapter 5, which the series is tracked by.
     */
    @Test
    fun `finishing a sibling's copy pushes the trunk's number`() = runTest {
        subject(trackedChapterNumber = trunkNumbers()).finish(siblingCopy, listOf(1L, 2L), stitch) {}
        runCurrent()

        coVerify { trackNovelChapter.await(any(), 2L, 5.0) }
    }

    @Test
    fun `a refused push of a sibling's copy queues the trunk's number`() = runTest {
        val store = mockk<NovelDelayedTrackingStore>(relaxed = true)
        val refusing = TrackNovelChapter(
            getNovelTracks = mockk { coEvery { awaitGroup(2L) } returns listOf(novelTrack()) },
            trackerManager = refusingTracker(),
            upsertNovelTrack = mockk(relaxed = true),
            delayedTrackingStore = store,
        )

        subject(trackNovelChapter = refusing, trackedChapterNumber = trunkNumbers())
            .finish(siblingCopy, listOf(1L, 2L), stitch) {}
        runCurrent()

        verify { store.add(1L, 5.0) }
    }

    private val siblingCopy = chapter(205L, novelId = 2L, number = 4.0)

    private fun trunkNumbers() = mockk<TrackedChapterNumber> {
        coEvery { ofNovels(listOf(siblingCopy)) } returns listOf(5.0)
    }

    private fun refusingTracker(): TrackerManager {
        val tracker = mockk<BaseTracker> {
            every { id } returns 3L
            every { isLoggedIn } returns true
            every { supportsReadingDates } returns false
            coEvery { refresh(any()) } answers { firstArg() }
            coEvery { update(any(), any()) } throws HttpException(429)
        }
        return mockk { every { get(3L) } returns tracker }
    }

    private fun novelTrack() = NovelTrack(
        id = 1L, novelId = 2L, trackerId = 3L, remoteId = 9L, libraryId = null, title = "", lastChapterRead = 0.0,
        totalChapters = 0L, status = 0L, score = 0.0, remoteUrl = "", startDate = null, finishDate = null,
        private = false,
    )

    /** The push is network, and the reader's other writes and its seamless crossings wait on a finish. */
    @Test
    fun `finishing a chapter does not wait for the tracker push`() = runTest {
        coEvery { trackNovelChapter.await(any(), any(), any(), any()) } coAnswers { awaitCancellation() }
        var trimmed = false

        withTimeout(1_000) {
            subject().finish(finished, memberIds = listOf(1L, 2L), stitch = stitch) { trimmed = true }
        }

        trimmed shouldBe true
    }

    @Test
    fun `finishing a chapter pushes nothing to the trackers when auto update is off`() = runTest {
        val off = InMemoryPreferenceStore(
            sequenceOf(InMemoryPreferenceStore.InMemoryPreference("pref_auto_update_manga_sync_key", false, true)),
        )

        subject(trackStore = off).finish(finished, memberIds = listOf(1L, 2L), stitch = stitch) {}
        runCurrent()

        coVerify(exactly = 0) { trackNovelChapter.await(any(), any(), any(), any()) }
    }

    @Test
    fun `the trim behind runs after the chapter is marked read`() = runTest {
        var marked = false
        coEvery { repo.setReadBulk(listOf(5L, 55L, 205L), true) } answers {
            marked = true
            true
        }
        val markedWhenTrimmed = mutableListOf<Boolean>()

        subject().finish(finished, memberIds = listOf(1L, 2L), stitch = stitch) { markedWhenTrimmed += marked }

        markedWhenTrimmed shouldBe listOf(true)
    }

    /** Mihon's reader never runs its manual-mark deletion; its own slot rule trims behind it instead. */
    @Test
    fun `finishing a chapter in the reader does not delete it as if marked read by hand`() = runTest {
        subject().finish(finished, memberIds = listOf(1L, 2L), stitch = stitch) {}

        coVerify(exactly = 0) { deleteAfterRead.await(any()) }
    }

    @Test
    fun `a chapter released after finishing can be finished again`() = runTest {
        val finish = subject()
        finish.finish(finished, memberIds = listOf(1L, 2L), stitch = stitch) {}

        finish.release(listOf(5L))
        finish.finish(finished, memberIds = listOf(1L, 2L), stitch = stitch) {}
        runCurrent()

        coVerify(exactly = 2) { trackNovelChapter.await(any(), 1L, 5.0) }
    }
}
