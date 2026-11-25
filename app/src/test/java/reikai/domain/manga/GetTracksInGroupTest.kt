package reikai.domain.manga

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track

class GetTracksInGroupTest {

    private val preferences = ReikaiLibraryPreferences(EmittingPreferenceStore())
    private val getTracks = mockk<GetTracks>()
    private val mergeManager = mockk<MangaMergeManager> {
        every { relatedIdsChanges() } returns flowOf(Unit)
    }
    private val interactor = GetTracksInGroup(preferences, getTracks, mergeManager)

    private fun track(mangaId: Long, trackerId: Long, lastChapterRead: Double = 0.0) = Track(
        id = -1L,
        mangaId = mangaId,
        trackerId = trackerId,
        remoteId = 100L,
        libraryId = null,
        title = "title",
        lastChapterRead = lastChapterRead,
        totalChapters = 0L,
        status = 0L,
        score = 0.0,
        remoteUrl = "",
        startDate = null,
        finishDate = null,
        private = false,
    )

    private fun group(vararg ids: Long) {
        coEvery { mergeManager.relatedIdsList(any()) } returns ids.toList()
    }

    @Test
    fun `unions the tracks of every group member`() = runTest {
        group(1L, 2L)
        coEvery { getTracks.await(1L) } returns listOf(track(1L, trackerId = 10L))
        coEvery { getTracks.await(2L) } returns listOf(track(2L, trackerId = 20L))

        interactor.await(1L).map { it.trackerId } shouldContainExactlyInAnyOrder listOf(10L, 20L)
    }

    @Test
    fun `an ungrouped manga reads its own tracks`() = runTest {
        group(7L)
        coEvery { getTracks.await(7L) } returns listOf(track(7L, trackerId = 10L))

        interactor.await(7L).map { it.mangaId } shouldContainExactly listOf(7L)
    }
}
