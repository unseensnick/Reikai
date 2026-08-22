package reikai.domain.recommendation

import eu.kanade.tachiyomi.data.track.TrackerManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.recommendation.taste.TasteLibraryRepository
import reikai.domain.recommendation.taste.TrackStatus
import reikai.domain.recommendation.taste.TrackedEntry
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.track.interactor.GetTracksPerManga

/**
 * The anti-echo hide index reads the raw cache on purpose, so an adult entry the user already tracks
 * stays suppressed even while the adult-content setting is hiding it from the taste profile.
 * Filtering here would make adult titles start appearing as suggestions, the opposite of the
 * setting's promise, and it is the asymmetry a later refactor is most likely to flatten.
 */
class BuildRecommendationHideFilterAdultTest {

    private val explicit = TrackedEntry(
        trackerId = 7L,
        remoteId = 42L,
        title = "Explicit",
        score = -1.0,
        status = TrackStatus.READING,
        tags = listOf("hentai"),
    )

    private fun boolPreference(value: Boolean) = mockk<Preference<Boolean>>().also {
        every { it.get() } returns value
    }

    @Test
    fun `an adult entry still lands in the hide index`() = runTest {
        val repository = mockk<TasteLibraryRepository>()
        coEvery { repository.getAll() } returns listOf(explicit)

        val preferences = mockk<ReikaiRecommendationPreferences>()
        every { preferences.hideInLibraryRecommendations } returns boolPreference(false)
        every { preferences.hideTrackedReadingCompleted } returns boolPreference(true)
        every { preferences.hideTrackedDropped } returns boolPreference(false)
        every { preferences.hideTrackedOnHold } returns boolPreference(false)
        every { preferences.hideTrackedPlanToRead } returns boolPreference(false)

        val getFavorites = mockk<GetFavorites>()
        coEvery { getFavorites.await() } returns emptyList()
        val getTracksPerManga = mockk<GetTracksPerManga>()
        every { getTracksPerManga.subscribe() } returns flowOf(emptyMap())

        val trackerManager = mockk<TrackerManager>()
        every { trackerManager.aniList.id } returns 2L
        every { trackerManager.myAnimeList.id } returns 1L

        val filter = BuildRecommendationHideFilter(
            getFavorites = getFavorites,
            getTracksPerManga = getTracksPerManga,
            repository = repository,
            preferences = preferences,
            localTrackStatusMapper = mockk(),
            trackerManager = trackerManager,
        ).await()

        filter.isNoOp shouldBe false
    }
}
