package reikai.domain.recommendation

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.app.di.AppBindings
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class RecsKindCase(private val label: String, val provider: (OkHttpClient) -> TrackerRecommendations) {
    override fun toString() = label
}

/**
 * An untracked manga finds its tracker entry by title, and a light novel sharing that title must not
 * stand in for it: the title search answers the novel (id 2) first and the manga (id 1) second, and
 * each id's recommendations are titled after it.
 */
class RecommendationSearchKindTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a title lookup takes the manga's recommendations`(case: RecsKindCase) = runTest {
        val client = OkHttpClient.Builder().addInterceptor(FakeRecsServer).build()
        case.provider(client).getRecsBySearch("Overlord").map { it.manga.title } shouldBe listOf("Manga rec")
    }

    companion object {
        private val json = AppBindings.providesJson()

        @JvmStatic
        fun cases() = listOf(
            RecsKindCase("AniList") { AnilistRecommendations(it, 2, json) },
            RecsKindCase("MyAnimeList") { MyAnimeListRecommendations(it, 1, json) },
            RecsKindCase("MangaUpdates") { MangaUpdatesRecommendations(it, 7, json) },
        )
    }
}

private object FakeRecsServer : Interceptor {
    private fun alMedia(format: String, rec: String) =
        """{"format":"$format","title":{"romaji":"Overlord"},"recommendations":{"edges":[{"node":""" +
            """{"mediaRecommendation":{"id":9,"siteUrl":"https://anilist.co/manga/9","title":{"english":"$rec"}}}}]}}"""

    private fun jikanRecs(rec: String) = """{"data":[{"entry":{"mal_id":9,"title":"$rec","url":"https://mal/9"}}]}"""

    private fun muSeries(rec: String) =
        """{"recommendations":[{"series_name":"$rec","series_url":"https://mu/9"}]}"""

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val body = when (request.url.encodedPath) {
            "/" -> """{"data":{"Page":{"media":[${alMedia("NOVEL", "Novel rec")},${alMedia("MANGA", "Manga rec")}]}}}"""
            "/v4/manga" -> """{"data":[{"mal_id":2,"type":"Light Novel"},{"mal_id":1,"type":"Manga"}]}"""
            "/v4/manga/1/recommendations" -> jikanRecs("Manga rec")
            "/v4/manga/2/recommendations" -> jikanRecs("Novel rec")
            "/v1/series/search" ->
                """{"results":[{"record":{"series_id":2,"type":"Novel"}},""" +
                    """{"record":{"series_id":1,"type":"Manga"}}]}"""
            "/v1/series/1" -> muSeries("Manga rec")
            "/v1/series/2" -> muSeries("Novel rec")
            else -> error("unexpected ${request.url}")
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }
}
