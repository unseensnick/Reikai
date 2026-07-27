package reikai.domain.manga

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track

class GetTracksInGroupTest {

    private val sharingPref = mockk<Preference<Boolean>> {
        every { get() } returns true
    }
    private val preferences = mockk<ReikaiLibraryPreferences> {
        every { syncTrackerLinksGrouped } returns sharingPref
    }
    private val getTracks = mockk<GetTracks>()
    private val mergeManager = mockk<MangaMergeManager>()
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
        startDate = 0L,
        finishDate = 0L,
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
    fun `a tracker copied onto several members reads as the furthest-read row`() = runTest {
        group(1L, 2L)
        coEvery { getTracks.await(1L) } returns listOf(track(1L, trackerId = 10L, lastChapterRead = 5.0))
        coEvery { getTracks.await(2L) } returns listOf(track(2L, trackerId = 10L, lastChapterRead = 21.0))

        interactor.await(1L).map { it.mangaId to it.lastChapterRead } shouldContainExactly listOf(2L to 21.0)
    }

    @Test
    fun `sharing turned off reads the entry's own tracks only`() = runTest {
        every { sharingPref.get() } returns false
        group(1L, 2L)
        coEvery { getTracks.await(1L) } returns listOf(track(1L, trackerId = 10L))

        interactor.await(1L).map { it.mangaId } shouldContainExactly listOf(1L)
        coVerify(exactly = 0) { getTracks.await(2L) }
    }

    @Test
    fun `an ungrouped manga reads its own tracks`() = runTest {
        group(7L)
        coEvery { getTracks.await(7L) } returns listOf(track(7L, trackerId = 10L))

        interactor.await(7L).map { it.mangaId } shouldContainExactly listOf(7L)
    }

    @Test
    fun `subscribe emits the entry's own tracks before the group resolves`() = runTest {
        // The membership lookup hits the database, which is why the first emission skips it.
        coEvery { mergeManager.relatedIdsList(any()) } coAnswers {
            delay(10)
            listOf(1L, 2L)
        }
        every { getTracks.subscribe(1L) } returns flowOf(listOf(track(1L, trackerId = 10L)))
        every { getTracks.subscribe(2L) } returns flowOf(listOf(track(2L, trackerId = 20L)))

        val emissions = interactor.subscribe(1L).toList()

        emissions.first().map { it.trackerId } shouldContainExactly listOf(10L)
        emissions.last().map { it.trackerId } shouldContainExactlyInAnyOrder listOf(10L, 20L)
    }
}
