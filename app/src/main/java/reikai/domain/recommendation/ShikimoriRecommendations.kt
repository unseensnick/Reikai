package reikai.domain.recommendation

import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import reikai.domain.recommendation.dto.SMMangaDetail
import reikai.domain.recommendation.dto.SMRecsManga

/**
 * Shikimori similar-manga recommendations (`/api/mangas/{id}/similar`). The endpoint is public, so
 * no OAuth is used (reusing Shikimori's auth interceptor would throw for a logged-out user). But
 * Shikimori IP-bans on a browser User-Agent, so every request here carries the registered app-name
 * UA, which the shared client's default UA interceptor leaves untouched when already set.
 *
 * The compact manga object carries no synonyms, so candidates dedup on primary title only.
 */
class ShikimoriRecommendations(
    private val client: OkHttpClient,
    override val trackerId: Long,
) : TrackerRecommendations() {

    override val trackerName: String = "Shikimori"

    private val headers = Headers.headersOf("User-Agent", USER_AGENT)

    override suspend fun getRecsById(remoteId: Long): List<RelatedMangaCandidate> {
        val url = "$API_URL/mangas".toHttpUrl().newBuilder()
            .addPathSegment(remoteId.toString())
            .addPathSegment("similar")
            .build()

        val data = with(json) { client.newCall(GET(url, headers)).awaitSuccess().parseAs<List<SMRecsManga>>() }
        return data.map { it.toCandidate() }
    }

    override suspend fun getMediaContext(remoteId: Long): MediaContext {
        val url = "$API_URL/mangas".toHttpUrl().newBuilder()
            .addPathSegment(remoteId.toString())
            .build()
        val data = with(json) { client.newCall(GET(url, headers)).awaitSuccess().parseAs<SMMangaDetail>() }
        return MediaContext(genres = data.genres.map { it.name }, recommendations = getRecsById(remoteId))
    }

    override suspend fun getRecsBySearch(title: String): List<RelatedMangaCandidate> {
        val url = "$API_URL/mangas".toHttpUrl().newBuilder()
            .addQueryParameter("search", title)
            .addQueryParameter("limit", "1")
            .build()

        val data = with(json) { client.newCall(GET(url, headers)).awaitSuccess().parseAs<List<SMRecsManga>>() }
        val firstId = data.firstOrNull()?.id ?: return emptyList()
        return getRecsById(firstId)
    }

    private fun SMRecsManga.toCandidate(): RelatedMangaCandidate = candidate(
        url = BASE_URL + url,
        title = name,
        thumbnailUrl = (image?.original ?: image?.preview)?.let { BASE_URL + it },
        remoteId = id,
    )

    companion object {
        private const val BASE_URL = "https://shikimori.one"
        private const val API_URL = "$BASE_URL/api"

        // Shikimori requires the registered app name as User-Agent (a browser UA risks an IP ban);
        // matches eu.kanade.tachiyomi.data.track.shikimori.ShikimoriInterceptor.
        private val USER_AGENT = "Mihon v${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})"
    }
}
