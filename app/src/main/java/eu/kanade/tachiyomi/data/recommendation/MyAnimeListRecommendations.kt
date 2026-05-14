package eu.kanade.tachiyomi.data.recommendation

import eu.kanade.tachiyomi.data.recommendation.dto.JikanImages
import eu.kanade.tachiyomi.data.recommendation.dto.JikanRecsResponse
import eu.kanade.tachiyomi.data.recommendation.dto.JikanSearchResponse
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * MyAnimeList recommendations via the public Jikan API. Matches Komikku — the official MAL API
 * has no recommendations endpoint, so Jikan is the standard substitute. Public, no auth.
 */
class MyAnimeListRecommendations(
    private val client: OkHttpClient,
) : TrackerRecommendations() {
    override val trackerName: String = "MyAnimeList"

    override suspend fun getRecsById(remoteId: Long): List<SManga> {
        val url = ENDPOINT.toHttpUrl()
            .newBuilder()
            .addPathSegment("manga")
            .addPathSegment(remoteId.toString())
            .addPathSegment("recommendations")
            .build()

        val data = client.newCall(GET(url)).awaitSuccess().parseAs<JikanRecsResponse>()
        return data.data.map { it.entry }.map { rec ->
            SManga.create().apply {
                this.url = rec.url
                this.title = rec.title
                this.thumbnail_url = rec.images?.pickImage()
                this.initialized = true
            }
        }
    }

    override suspend fun getRecsBySearch(title: String): List<SManga> {
        val url = ENDPOINT.toHttpUrl()
            .newBuilder()
            .addPathSegment("manga")
            .addQueryParameter("q", title)
            .build()

        val data = client.newCall(GET(url)).awaitSuccess().parseAs<JikanSearchResponse>()
        val firstMalId = data.data.firstOrNull()?.malId ?: return emptyList()
        return getRecsById(firstMalId)
    }

    private fun JikanImages.pickImage(): String? =
        webp?.imageUrl ?: jpg?.imageUrl

    companion object {
        private const val ENDPOINT = "https://api.jikan.moe/v4/"
    }
}
