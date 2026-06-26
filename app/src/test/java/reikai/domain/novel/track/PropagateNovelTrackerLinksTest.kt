package reikai.domain.novel.track

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.InsertNovelTrack
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelTrack
import tachiyomi.core.common.preference.Preference

class PropagateNovelTrackerLinksTest {

    private val syncPref = mockk<Preference<Boolean>>()
    private val preferences = mockk<ReikaiLibraryPreferences> {
        every { syncTrackerLinksGrouped } returns syncPref
    }
    private val mergeManager = mockk<NovelMergeManager>()
    private val novelRepository = mockk<NovelRepository>()
    private val getNovelTracks = mockk<GetNovelTracks>()
    private val inserted = mutableListOf<NovelTrack>()
    private val insertNovelTrack = mockk<InsertNovelTrack> {
        coEvery { await(any()) } answers { inserted += firstArg<NovelTrack>() }
    }

    private val interactor =
        PropagateNovelTrackerLinks(preferences, mergeManager, novelRepository, getNovelTracks, insertNovelTrack)

    private fun track(novelId: Long, trackerId: Long, remoteId: Long) = NovelTrack(
        id = -1L,
        novelId = novelId,
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

    private fun novel(favorite: Boolean = true) = mockk<Novel> {
        every { this@mockk.favorite } returns favorite
    }

    private fun group(vararg ids: Long) {
        every { syncPref.get() } returns true
        coEvery { mergeManager.relatedNovelIdsFor(any()) } returns ids.toList()
    }

    @Test
    fun `mirrors a tracker onto every other favorited member`() = runTest {
        group(1L, 2L, 3L)
        coEvery { novelRepository.getById(any()) } returns novel()
        coEvery { getNovelTracks.await(1L) } returns listOf(track(1L, 10L, 100L))
        coEvery { getNovelTracks.await(2L) } returns emptyList()
        coEvery { getNovelTracks.await(3L) } returns emptyList()

        interactor.fromSeed(1L)

        inserted.map { it.novelId to it.trackerId } shouldContainExactlyInAnyOrder listOf(2L to 10L, 3L to 10L)
    }

    @Test
    fun `does nothing when the pref is off`() = runTest {
        every { syncPref.get() } returns false

        interactor.distribute(listOf(1L, 2L))

        coVerify(exactly = 0) { insertNovelTrack.await(any()) }
    }

    @Test
    fun `skips a tracker whose remote id conflicts across members`() = runTest {
        group(1L, 2L)
        coEvery { novelRepository.getById(any()) } returns novel()
        coEvery { getNovelTracks.await(1L) } returns listOf(track(1L, 10L, 100L))
        coEvery { getNovelTracks.await(2L) } returns listOf(track(2L, 10L, 999L))

        interactor.fromSeed(1L)

        coVerify(exactly = 0) { insertNovelTrack.await(any()) }
    }

    @Test
    fun `does not link a tracker onto an unfavorited member`() = runTest {
        group(1L, 2L, 3L)
        coEvery { novelRepository.getById(1L) } returns novel()
        coEvery { novelRepository.getById(2L) } returns novel()
        coEvery { novelRepository.getById(3L) } returns novel(favorite = false)
        coEvery { getNovelTracks.await(1L) } returns listOf(track(1L, 10L, 100L))
        coEvery { getNovelTracks.await(2L) } returns emptyList()

        interactor.fromSeed(1L)

        inserted.map { it.novelId } shouldContainExactlyInAnyOrder listOf(2L)
    }

    @Test
    fun `does not re-insert a tracker a member already has`() = runTest {
        group(1L, 2L)
        coEvery { novelRepository.getById(any()) } returns novel()
        coEvery { getNovelTracks.await(1L) } returns listOf(track(1L, 10L, 100L))
        coEvery { getNovelTracks.await(2L) } returns listOf(track(2L, 10L, 100L))

        interactor.fromSeed(1L)

        coVerify(exactly = 0) { insertNovelTrack.await(any()) }
    }
}
