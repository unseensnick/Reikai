package eu.kanade.tachiyomi.data.track.novellist

import eu.kanade.tachiyomi.data.track.novellist.dto.NLFilterRequest
import eu.kanade.tachiyomi.data.track.novellist.dto.NLNovel
import eu.kanade.tachiyomi.data.track.novellist.dto.NLReadingListEntry
import eu.kanade.tachiyomi.data.track.novellist.dto.NLUpdateRequest
import eu.kanade.tachiyomi.data.track.novellist.dto.NLUser
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import kotlin.time.Duration.Companion.minutes

class NovelListApi(
    interceptor: NovelListInterceptor,
    client: OkHttpClient,
    private val apiUrl: () -> String,
) {
    private val json: Json by injectLazy()

    // Our own ceiling, not theirs: the service publishes no limit and returns no rate-limit headers,
    // and it runs on one small deployment, so a tracker's handful of calls stays well under this.
    private val rateLimitedClient = client.newBuilder()
        .rateLimit(permits = 30, period = 1.minutes)
        .build()

    private val authClient by lazy {
        rateLimitedClient.newBuilder()
            .addInterceptor(interceptor)
            .build()
    }

    /** Search is public; the JWT is neither required nor sent. */
    suspend fun searchNovels(query: String): List<NLNovel> {
        val body = json.encodeToString(NLFilterRequest(titleSearchQuery = query))
            .toRequestBody(JSON_MEDIA_TYPE)
        return with(json) {
            rateLimitedClient.newCall(POST("${apiUrl()}/novels/filter", body = body))
                .awaitSuccess()
                .parseAs<List<NLNovel>>()
        }
    }

    /** Also public, and the only shape carrying `author`. The path rejects a slug with 422. */
    suspend fun getNovel(novelId: String): NLNovel {
        return with(json) {
            rateLimitedClient.newCall(GET("${apiUrl()}/novels/$novelId"))
                .awaitSuccess()
                .parseAs<NLNovel>()
        }
    }

    suspend fun getCurrentUser(): NLUser {
        return with(json) {
            authClient.newCall(GET("${apiUrl()}/users/current"))
                .awaitSuccess()
                .parseAs<NLUser>()
        }
    }

    suspend fun getReadingListEntry(novelId: String): NLReadingListEntry {
        return with(json) {
            authClient.newCall(GET("${apiUrl()}/users/current/reading-list/$novelId"))
                .awaitSuccess()
                .parseAs<NLReadingListEntry>()
        }
    }

    suspend fun updateReadingListEntry(novelId: String, entry: NLUpdateRequest) {
        val body = json.encodeToString(entry).toRequestBody(JSON_MEDIA_TYPE)
        authClient.newCall(PUT("${apiUrl()}/users/current/reading-list/$novelId", body = body))
            .awaitSuccess()
            .close()
    }

    suspend fun deleteReadingListEntry(novelId: String) {
        authClient.newCall(DELETE("${apiUrl()}/users/current/reading-list/$novelId"))
            .awaitSuccess()
            .close()
    }

    companion object {
        const val BASE_URL = "https://www.novellist.co"

        // The backend answers on its generated Cloud Run hostname, which is not the site's domain and
        // carries their project number, so a move would brick the client. That is why the tracker can
        // override this. It is the only host the OpenAPI document describes.
        const val DEFAULT_API_URL = "https://novellist-be-960019704910.asia-east1.run.app/api"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun novelUrl(slug: String): String = "$BASE_URL/novels/$slug"
    }
}
