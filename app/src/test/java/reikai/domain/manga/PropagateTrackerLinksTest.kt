package reikai.domain.manga

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.interactor.InsertTrack
import tachiyomi.domain.track.model.Track

class PropagateTrackerLinksTest {

    private val syncPref = mockk<Preference<Boolean>>()
    private val preferences = mockk<ReikaiLibraryPreferences> {
        every { syncTrackerLinksGrouped } returns syncPref
    }
    private val mergeManager = mockk<MangaMergeManager>()
    private val getManga = mockk<GetManga>()
    private val getTracks = mockk<GetTracks>()
    private val insertTrack = mockk<InsertTrack>()

    private val interactor = PropagateTrackerLinks(preferences, mergeManager, getManga, getTracks, insertTrack)

    private fun track(mangaId: Long, trackerId: Long, remoteId: Long) = Track(
        id = -1L,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = remoteId,
        libraryId = null,
        title = "title",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = 0.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private fun manga(id: Long, favorite: Boolean = true) = mockk<Manga> {
        every { this@mockk.favorite } returns favorite
        every { this@mockk.title } returns "Shared title"
    }

    private fun group(vararg ids: Long) {
        every { syncPref.get() } returns true
        coEvery { mergeManager.computeRelatedMangaIds(any(), any()) } returns
            MangaMergeManager.RelatedIdsResult(ids, 0)
    }

    @Test
    fun `mirrors a tracker onto every other favorited member`() = runTest {
        group(1L, 2L, 3L)
        coEvery { getManga.await(any()) } answers { manga(firstArg()) }
        coEvery { getTracks.await(1L) } returns listOf(track(1L, 10L, 100L))
        coEvery { getTracks.await(2L) } returns emptyList()
        coEvery { getTracks.await(3L) } returns emptyList()
        val inserted = slot<List<Track>>()
        coEvery { insertTrack.awaitAll(capture(inserted)) } returns Unit

        interactor.fromSeed(1L)

        inserted.captured.map { it.mangaId to it.trackerId } shouldContainExactlyInAnyOrder
            listOf(2L to 10L, 3L to 10L)
    }

    @Test
    fun `does nothing when the pref is off`() = runTest {
        every { syncPref.get() } returns false

        interactor.fromSeed(1L)

        coVerify(exactly = 0) { insertTrack.awaitAll(any()) }
    }

    @Test
    fun `skips a tracker whose remote id conflicts across members`() = runTest {
        group(1L, 2L)
        coEvery { getManga.await(any()) } answers { manga(firstArg()) }
        coEvery { getTracks.await(1L) } returns listOf(track(1L, 10L, 100L))
        coEvery { getTracks.await(2L) } returns listOf(track(2L, 10L, 999L)) // same tracker, different series

        interactor.fromSeed(1L)

        coVerify(exactly = 0) { insertTrack.awaitAll(any()) }
    }

    @Test
    fun `does not link a tracker onto an unfavorited member`() = runTest {
        group(1L, 2L, 3L)
        coEvery { getManga.await(1L) } returns manga(1L)
        coEvery { getManga.await(2L) } returns manga(2L)
        coEvery { getManga.await(3L) } returns manga(3L, favorite = false)
        coEvery { getTracks.await(1L) } returns listOf(track(1L, 10L, 100L))
        coEvery { getTracks.await(2L) } returns emptyList()
        val inserted = slot<List<Track>>()
        coEvery { insertTrack.awaitAll(capture(inserted)) } returns Unit

        interactor.fromSeed(1L)

        inserted.captured.map { it.mangaId } shouldContainExactlyInAnyOrder listOf(2L)
    }

    @Test
    fun `does not re-insert a tracker a member already has`() = runTest {
        group(1L, 2L)
        coEvery { getManga.await(any()) } answers { manga(firstArg()) }
        coEvery { getTracks.await(1L) } returns listOf(track(1L, 10L, 100L))
        coEvery { getTracks.await(2L) } returns listOf(track(2L, 10L, 100L))

        interactor.fromSeed(1L)

        coVerify(exactly = 0) { insertTrack.awaitAll(any()) }
    }
}
