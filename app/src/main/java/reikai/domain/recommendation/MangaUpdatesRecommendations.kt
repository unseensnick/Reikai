package reikai.domain.recommendation

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import reikai.domain.recommendation.dto.MUSearchResponse
import reikai.domain.recommendation.dto.MUSeriesResponse

/**
 * MangaUpdates community recommendations via the public v1 API (`series/{id}` recommendations).
 * Public, no auth. No alternative titles parsed here, so candidates dedup on primary title only
 * (synonym parsing deferred, see docs/dev/development.md).
 */
class MangaUpdatesRecommendations(
    private val client: OkHttpClient,
    override val trackerId: Long,
) : TrackerRecommendations() {

    override val trackerName: String = "MangaUpdates"

    override suspend fun getRecsById(remoteId: Long): List<RelatedMangaCandidate> =
        fetchSeries(remoteId).toCandidates()

    // The series endpoint returns both recommendations and genres, so one call serves both.
    override suspend fun getMediaContext(remoteId: Long): MediaContext {
        val data = fetchSeries(remoteId)
        return MediaContext(genres = data.genres.map { it.genre }, recommendations = data.toCandidates())
    }

    private suspend fun fetchSeries(remoteId: Long): MUSeriesResponse {
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addPathSegment("series")
            .addPathSegment(remoteId.toString())
            .build()
        return with(json) { client.newCall(GET(url)).awaitSuccess().parseAs<MUSeriesResponse>() }
    }

    private fun MUSeriesResponse.toCandidates(): List<RelatedMangaCandidate> =
        recommendations.map { rec ->
            candidate(
                url = rec.seriesUrl,
                title = rec.seriesName,
                thumbnailUrl = rec.seriesImage?.url?.original,
            )
        }

    override suspend fun getRecsBySearch(title: String): List<RelatedMangaCandidate> {
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addPathSegments("series/search")
            .build()

        val payload = buildJsonObject {
            put("search", title)
            put("stype", "title")
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)

        val data = with(json) {
            client.newCall(POST(url.toString(), body = body)).awaitSuccess().parseAs<MUSearchResponse>()
        }
        val firstSeriesId = data.results.firstOrNull()?.record?.seriesId ?: return emptyList()
        return getRecsById(firstSeriesId)
    }

    companion object {
        private const val ENDPOINT = "https://api.mangaupdates.com/v1/"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
