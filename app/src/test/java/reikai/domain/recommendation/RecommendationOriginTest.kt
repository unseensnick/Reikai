package reikai.domain.recommendation

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.app.di.AppBindings
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * A provider's candidates carry the name and id of the tracker it was built for, the name Settings
 * shows for that tracker, never one spelled out in the provider.
 */
class RecommendationOriginTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `candidates carry their tracker's own name and id`(case: RecsKindCase) = runTest {
        case.provider().getRecsById(1L).map { it.origin to it.trackerId }.distinct() shouldBe
            listOf(RecommendationOrigin.Tracker("Tracker X") to 42L)
    }

    companion object {
        private val json = AppBindings.providesJson()

        @JvmStatic
        fun cases() = listOf(
            RecsKindCase("AniList") { AnilistRecommendations(FakeRecsServer.client, fakeTracker(), json) },
            RecsKindCase("MyAnimeList") { MyAnimeListRecommendations(FakeRecsServer.client, fakeTracker(), json) },
            RecsKindCase("MangaUpdates") { MangaUpdatesRecommendations(FakeRecsServer.client, fakeTracker(), json) },
            RecsKindCase("Shikimori") { ShikimoriRecommendations(FakeRecsServer.client, fakeTracker(), json) },
        )
    }
}
