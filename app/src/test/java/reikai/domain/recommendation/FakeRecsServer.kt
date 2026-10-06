package reikai.domain.recommendation

import eu.kanade.tachiyomi.data.track.Tracker
import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * The four recommendation endpoints over one title, Overlord: a title search answers the light novel
 * (id 2) before the manga (id 1), and each id's recommendations are titled after it.
 */
internal object FakeRecsServer : Interceptor {
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
            "/api/mangas/1/similar" -> """[{"id":9,"name":"Manga rec","url":"/mangas/9"}]"""
            else -> error("unexpected ${request.url}")
        }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()
}

/** A tracker named and numbered apart from every real one, so a provider can only have read it from here. */
internal fun fakeTracker(name: String = "Tracker X", id: Long = 42L): Tracker = mockk {
    every { this@mockk.name } returns name
    every { this@mockk.id } returns id
}
