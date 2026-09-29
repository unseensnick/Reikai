package reikai.domain.recommendation

import eu.kanade.tachiyomi.data.track.TrackerManager
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import reikai.data.track.installTrackerTestGraph
import reikai.domain.recommendation.taste.KitsuLibraryFetcher
import reikai.presentation.recents.EmittingPreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope

/**
 * Each tracker's recommendation switch and library-pull switch is listed once, and the settings screen,
 * the carousel's enabled set and the pull all read those lists. These pin the lists to what they gate.
 */
class RecommendationTogglesTest {

    private val trackerManager = TrackerManager()
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

    companion object {
        private lateinit var appScope: InjektScope

        @JvmStatic
        @BeforeAll
        fun installGraph() {
            appScope = installTrackerTestGraph()
        }

        @JvmStatic
        @AfterAll
        fun restoreGraph() {
            Injekt = appScope
        }
    }
}
