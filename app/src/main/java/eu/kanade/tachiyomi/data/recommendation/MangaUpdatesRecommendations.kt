package eu.kanade.tachiyomi.data.recommendation

import eu.kanade.tachiyomi.data.recommendation.dto.MUSearchResponse
import eu.kanade.tachiyomi.data.recommendation.dto.MUSeriesResponse
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * MangaUpdates community recommendations via the public v1 API. Komikku also exposes a "similar"
 * variant via `category_recommendations` — Phase 3 ships only the community list per the plan doc.
 */
class MangaUpdatesRecommendations(
    private val client: OkHttpClient,
) : TrackerRecommendations() {
    override val trackerName: String = "MangaUpdates"

    override suspend fun getRecsById(remoteId: Long): List<SManga> {
        val url = ENDPOINT.toHttpUrl()
            .newBuilder()
            .addPathSegment("series")
            .addPathSegment(remoteId.toString())
            .build()

        val data = client.newCall(GET(url)).awaitSuccess().parseAs<MUSeriesResponse>()
        return data.recommendations.map { rec ->
            SManga.create().apply {
                this.url = rec.seriesUrl
                this.title = rec.seriesName
                this.thumbnail_url = rec.seriesImage?.url?.original
                this.initialized = true
            }
        }
    }

    override suspend fun getRecsBySearch(title: String): List<SManga> {
        val url = ENDPOINT.toHttpUrl()
            .newBuilder()
            .addPathSegments("series/search")
            .build()

        val payload = buildJsonObject {
            put("search", title)
            put("stype", "title")
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val data = client.newCall(POST(url.toString(), body = body)).awaitSuccess().parseAs<MUSearchResponse>()
        val firstSeriesId = data.results.firstOrNull()?.record?.seriesId ?: return emptyList()
        return getRecsById(firstSeriesId)
    }

    companion object {
        private const val ENDPOINT = "https://api.mangaupdates.com/v1/"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
