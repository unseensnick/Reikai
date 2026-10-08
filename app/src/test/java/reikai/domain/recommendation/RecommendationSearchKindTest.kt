package reikai.domain.recommendation

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.app.di.AppBindings
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class RecsKindCase(private val label: String, val provider: () -> TrackerRecommendations) {
    override fun toString() = label
}

/**
 * An untracked manga finds its tracker entry by title, and a light novel sharing that title must not
 * stand in for it: [FakeRecsServer]'s title search answers the novel first.
 */
class RecommendationSearchKindTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a title lookup takes the manga's recommendations`(case: RecsKindCase) = runTest {
        case.provider().getRecsBySearch("Overlord").map { it.manga.title } shouldBe listOf("Manga rec")
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
