package reikai.domain.recommendation

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import reikai.domain.recommendation.dto.JikanImages
import reikai.domain.recommendation.dto.JikanRecsResponse
import reikai.domain.recommendation.dto.JikanSearchResponse

/**
 * MyAnimeList recommendations via the public Jikan API. MAL's own API has no recommendations
 * endpoint, so Jikan is the standard substitute. Public, no auth. Jikan reports no alternative
 * titles here, so these candidates dedup on primary title only (synonym parsing deferred, see
 * docs/dev/development.md).
 */
class MyAnimeListRecommendations(
    private val client: OkHttpClient,
) : TrackerRecommendations() {

    override val trackerName: String = "MyAnimeList"

    override suspend fun getRecsById(remoteId: Long): List<RelatedMangaCandidate> {
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addPathSegment("manga")
            .addPathSegment(remoteId.toString())
            .addPathSegment("recommendations")
            .build()

        val data = with(json) { client.newCall(GET(url)).awaitSuccess().parseAs<JikanRecsResponse>() }
        return data.data.map { it.entry }.map { rec ->
            candidate(url = rec.url, title = rec.title, thumbnailUrl = rec.images?.pickImage())
        }
    }

    override suspend fun getRecsBySearch(title: String): List<RelatedMangaCandidate> {
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addPathSegment("manga")
            .addQueryParameter("q", title)
            .build()

        val data = with(json) { client.newCall(GET(url)).awaitSuccess().parseAs<JikanSearchResponse>() }
        val firstMalId = data.data.firstOrNull()?.malId ?: return emptyList()
        return getRecsById(firstMalId)
    }

    private fun JikanImages.pickImage(): String? = webp?.imageUrl ?: jpg?.imageUrl

    companion object {
        private const val ENDPOINT = "https://api.jikan.moe/v4/"
    }
}
