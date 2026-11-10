package reikai.domain.recommendation

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import reikai.data.track.trackerTestManager
import reikai.domain.recommendation.taste.KitsuLibraryFetcher
import reikai.presentation.recents.EmittingPreferenceStore

/**
 * Each tracker's recommendation switch and library-pull switch is listed once, and the settings screen,
 * the carousel's enabled set and the pull all read those lists. These pin the lists to what they gate.
 */
class RecommendationTogglesTest {

    private val trackerManager = trackerTestManager()
    private val store = EmittingPreferenceStore()
    private val preferences = ReikaiRecommendationPreferences(store)

    @Test
    fun `the recommendation toggles name exactly the trackers with a recommendations endpoint`() {
        val providers = RecommendationProviders(mockk(relaxed = true), trackerManager, Json)

        preferences.recommendationToggles(trackerManager).map { it.tracker.id } shouldContainExactlyInAnyOrder
            trackerManager.trackers.filter { providers.forTracker(it.id) != null }.map { it.id }
    }

    @Test
    fun `with the tracker master switch off no tracker stream is enabled`() {
        preferences.includeTrackerRecommendations.set(false)

        preferences.enabledRecommendationTrackerIds(trackerManager) shouldBe emptySet()
    }

    @Test
    fun `with the tracker master switch off neither taste injection is enabled`() {
        preferences.includeTrackerRecommendations.set(false)

        preferences.enabledStreams(trackerManager) shouldBe
            EnabledRecommendationStreams(trackerIds = emptySet(), crossRecs = false, tagSearch = false)
    }

    @Test
    fun `a tracker switched off leaves the others enabled`() {
        preferences.shikimoriRecommendations.set(false)

        preferences.enabledRecommendationTrackerIds(trackerManager) shouldBe setOf(
            trackerManager.aniList.id,
            trackerManager.myAnimeList.id,
            trackerManager.mangaUpdates.id,
        )
    }

    @Test
    fun `a pull the user asked for waits for the tracker's login`() {
        preferences.pullLibraryFromKitsu.set(true)
        val fetcher = KitsuLibraryFetcher(mockk { every { isLoggedIn } returns false }, preferences)

        fetcher.isEnabled() shouldBe false
    }
}
