package eu.kanade.tachiyomi.data.track.ranobedb

import eu.kanade.tachiyomi.data.track.ranobedb.dto.RDBSeries
import eu.kanade.tachiyomi.data.track.ranobedb.dto.RDBSeriesList
import eu.kanade.tachiyomi.data.track.ranobedb.dto.RDBSeriesListEntry
import eu.kanade.tachiyomi.data.track.ranobedb.dto.RDBSeriesOne
import eu.kanade.tachiyomi.data.track.ranobedb.dto.RDBUser
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import java.net.URLEncoder
import kotlin.time.Duration.Companion.minutes

class RanobeDbApi(
    interceptor: RanobeDbInterceptor,
    client: OkHttpClient,
) {
    private val json: Json by injectLazy()

    // The published docs ask for no more than 60 requests a minute, so stay under it rather than
    // at it. Both clients share the limiter because they hit the same host.
    private val rateLimitedClient = client.newBuilder()
        .rateLimit(permits = 55, period = 1.minutes)
        .build()

    private val authClient by lazy {
        rateLimitedClient.newBuilder()
            .addInterceptor(interceptor)
            .build()
    }

    /** Search is public; a token is neither required nor sent. */
    suspend fun searchSeries(query: String): List<RDBSeries> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return with(json) {
            rateLimitedClient.newCall(GET("$API_URL/series?q=$encoded&limit=$SEARCH_LIMIT"))
                .awaitSuccess()
                .parseAs<RDBSeriesList>()
                .series
                .filterNot { it.hidden }
        }
    }

    suspend fun getSeries(seriesId: Long): RDBSeries {
        return with(json) {
            rateLimitedClient.newCall(GET("$API_URL/series/$seriesId"))
                .awaitSuccess()
                .parseAs<RDBSeriesOne>()
                .series
        }
    }

    suspend fun getCurrentUser(): RDBUser {
        return with(json) {
            authClient.newCall(GET("$API_URL/user/me"))
                .awaitSuccess()
                .parseAs<RDBUser>()
        }
    }

    suspend fun updateSeriesListEntry(seriesId: Long, entry: RDBSeriesListEntry) {
        val body = json.encodeToString(entry).toRequestBody(JSON_MEDIA_TYPE)
        authClient.newCall(PUT("$API_URL/user/series/$seriesId", body = body))
            .awaitSuccess()
            .close()
    }

    suspend fun deleteSeriesListEntry(seriesId: Long) {
        authClient.newCall(DELETE("$API_URL/user/series/$seriesId"))
            .awaitSuccess()
            .close()
    }

    companion object {
        const val BASE_URL = "https://ranobedb.org"
        private const val API_URL = "$BASE_URL/api/v0"
        private const val IMAGES_URL = "https://images.ranobedb.org"

        // The endpoint defaults to 24 and caps at 100.
        private const val SEARCH_LIMIT = 50

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun seriesUrl(seriesId: Long): String = "$BASE_URL/series/$seriesId"

        fun coverUrl(filename: String): String = "$IMAGES_URL/$filename"
    }
}
