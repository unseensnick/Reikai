package eu.kanade.tachiyomi.data.track.shikimori

import android.net.Uri
import androidx.core.net.toUri
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.network.okHttpClient
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMLibraryIdResponse
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMOAuth
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUser
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUserRate
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMUserRatesResponse
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.dataOrElse
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mihon.graphql.shikimori.ReikaiShikimoriGetMangaMetadataQuery
import mihon.graphql.shikimori.ReikaiShikimoriGetNovelDetailsQuery
import mihon.graphql.shikimori.ReikaiShikimoriSearchNovelQuery
import mihon.graphql.shikimori.ShikimoriGetCurrentUserQuery
import mihon.graphql.shikimori.ShikimoriGetLibMangaQuery
import mihon.graphql.shikimori.ShikimoriGetMangaDetailsQuery
import mihon.graphql.shikimori.ShikimoriSearchMangaQuery
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import tachiyomi.domain.track.model.Track as DomainTrack

class ShikimoriApi(
    private val trackerId: Long,
    private val client: OkHttpClient,
    interceptor: ShikimoriInterceptor,
) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    private val graphQlClient by lazy {
        ApolloClient.Builder()
            .serverUrl("$API_URL/graphql")
            .okHttpClient(authClient)
            .dispatcher(Dispatchers.IO)
            // required to log the error body in dataOrElse, which also properly closes it
            .httpExposeErrorBody(true)
            .build()
    }

    suspend fun addLibManga(track: Track, userId: String): Track {
        return withIOContext {
            with(json) {
                val payload = buildJsonObject {
                    putJsonObject("user_rate") {
                        put("user_id", userId)
                        put("target_id", track.remote_id)
                        put("target_type", "Manga")
                        put("chapters", track.last_chapter_read.toInt())
                        put("score", track.score.toInt())
                        put("status", track.toShikimoriStatus())
                    }
                }
                authClient.newCall(
                    POST(
                        "$API_URL/v2/user_rates",
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                ).awaitSuccess()
                    .parseAs<SMLibraryIdResponse>()
                    .let {
                        // save id of the entry for possible future delete request
                        track.library_id = it.id
                    }
                track
            }
        }
    }

    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val payload = buildJsonObject {
                putJsonObject("user_rate") {
                    put("chapters", track.last_chapter_read.toInt())
                    put("score", track.score.toInt())
                    put("status", track.toShikimoriStatus())
                }
            }

            with(json) {
                authClient.newCall(
                    PUT(
                        "$API_URL/v2/user_rates/${track.library_id}",
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<SMLibraryIdResponse>()
                    .let {
                        track.library_id = it.id
                    }
                track
            }
        }
    }

    suspend fun deleteLibManga(track: DomainTrack) {
        withIOContext {
            authClient
                .newCall(DELETE("$API_URL/v2/user_rates/${track.libraryId}"))
                .awaitSuccess()
        }
    }

    suspend fun search(search: String): List<TrackSearch> {
        return graphQlClient
            .query(
                ShikimoriSearchMangaQuery(search = search),
            )
            .execute()
            .dataOrElse(
                errorLog = "Shikimori: Search failed",
                default = { emptyList() },
            ) {
                it.mangas.map { manga ->
                    manga.toTrackSearch(trackerId)
                }
            }
    }

    suspend fun getMangaDetails(id: Int): TrackSearch? {
        return graphQlClient
            .query(
                ShikimoriGetMangaDetailsQuery(query = "$id"),
            )
            .execute()
            .dataOrElse(
                errorLog = "Shikimori: Failed to get manga details",
                default = { null },
            ) {
                it.mangas
                    .firstOrNull()
                    ?.toTrackSearch(trackerId)
            }
    }

    // RK --> novel-aware search and id lookup: the mangas query spans the ranobe catalog, so Reikai's
    // own operations flip the kind filter upstream's hardcode to the novel kinds.
    suspend fun searchNovel(search: String): List<TrackSearch> {
        return graphQlClient
            .query(ReikaiShikimoriSearchNovelQuery(search = search))
            .execute()
            .dataOrElse(
                errorLog = "Shikimori: Novel search failed",
                default = { emptyList() },
            ) {
                it.mangas.map { novel -> novel.toTrackSearch(trackerId) }
            }
    }

    suspend fun getNovelDetails(id: Int): TrackSearch? {
        return graphQlClient
            .query(ReikaiShikimoriGetNovelDetailsQuery(query = "$id"))
            .execute()
            .dataOrElse(
                errorLog = "Shikimori: Failed to get novel details",
                default = { null },
            ) {
                it.mangas.firstOrNull()?.toTrackSearch(trackerId)
            }
    }

    // "Fill from tracker" metadata (ported from Komikku; poster{mainUrl} per Reikai, plus genres).
    suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        val manga = graphQlClient
            .query(ReikaiShikimoriGetMangaMetadataQuery(ids = track.remoteId.toString()))
            .execute()
            .dataOrElse(
                errorLog = "Shikimori: Failed to get manga metadata",
                default = { null },
            ) { it.mangas.firstOrNull() }
            ?: throw Exception("Could not get metadata from Shikimori")
        fun credits(role: String) = manga.personRoles.orEmpty()
            .filter { personRole -> isCredited(personRole.rolesEn, role) }
            .joinToString(", ") { it.person.name }
            .ifEmpty { null }
        return TrackMangaMetadata(
            remoteId = manga.id.toLong(),
            title = manga.name,
            thumbnailUrl = manga.poster?.mainUrl,
            description = manga.description,
            authors = credits("Story"),
            artists = credits("Art"),
            genres = manga.genres?.map { it.name }?.takeIf { it.isNotEmpty() },
        )
    }

    // Full library pull for the recommendation taste profile via GraphQL userRates (genres inline;
    // the v2 REST user_rates has none), paged 50 per page through the authed client. Stays on raw
    // JSON: its DTOs are pinned by the taste-profile tests.
    suspend fun getUserLibrary(userId: Int): List<SMUserRate> {
        return withIOContext {
            val results = mutableListOf<SMUserRate>()
            var page = 1
            while (true) {
                val rates = fetchUserRatesPage(userId, page)
                results += rates
                if (rates.size < USER_RATES_PAGE_LIMIT) break
                page++
            }
            results
        }
    }

    private suspend fun fetchUserRatesPage(userId: Int, page: Int): List<SMUserRate> {
        val query = """
            |query {
            |  userRates(userId: $userId, targetType: Manga, page: $page, limit: $USER_RATES_PAGE_LIMIT) {
            |    score
            |    status
            |    manga { id name genres { name } }
            |  }
            |}
        """.trimMargin()
        val payload = buildJsonObject { put("query", query) }
        return with(json) {
            authClient.newCall(POST("$API_URL/graphql", body = payload.toString().toRequestBody(jsonMime)))
                .awaitSuccess()
                .parseAs<SMUserRatesResponse>()
                .data.userRates
        }
    }
    // RK <--

    suspend fun findLibManga(track: Track): Track? {
        return graphQlClient
            .query(
                ShikimoriGetLibMangaQuery(
                    remote_id = track.remote_id.toString(),
                ),
            )
            .execute()
            .dataOrElse(
                errorLog = "Shikimori: Failed to find manga in library",
                default = { null },
            ) {
                val mangaResult = it.mangas.firstOrNull()

                // Shikimori has no user list query that allows query by ID, so we go via the "mangas" query & include
                // userRate data which will be null if the title is not in the user's list.
                // If it was removed on Shikimori and is still linked in the app, notify user via returning null here
                // which throws an exception at the Shikimori.refresh call
                if (mangaResult?.userRate == null) {
                    null
                } else {
                    mangaResult.toTrack(trackerId)
                }
            }
    }

    suspend fun getCurrentUser(): SMUser {
        return graphQlClient
            .query(
                ShikimoriGetCurrentUserQuery(),
            )
            .execute()
            .dataOrElse(
                errorLog = "Shikimori: Failed to get current user",
                default = { null },
            ) {
                it.currentUser?.let { currentUser ->
                    SMUser(id = currentUser.id, nickname = currentUser.nickname)
                }
            }
            ?: throw Exception("Failed to get Shikimori user data")
    }

    suspend fun accessToken(code: String): SMOAuth {
        return withIOContext {
            with(json) {
                client.newCall(accessTokenRequest(code))
                    .awaitSuccess()
                    .parseAs()
            }
        }
    }

    private fun accessTokenRequest(code: String) = POST(
        OAUTH_URL,
        body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("code", code)
            .add("redirect_uri", REDIRECT_URL)
            .build(),
    )

    companion object {
        private const val BASE_URL = "https://shikimori.io"
        private const val API_URL = "$BASE_URL/api"
        private const val OAUTH_URL = "$BASE_URL/oauth/token"

        // RK: Shikimori GraphQL caps userRates at 50 per page.
        private const val USER_RATES_PAGE_LIMIT = 50
        private const val LOGIN_URL = "$BASE_URL/oauth/authorize"

        private const val REDIRECT_URL = "mihon://shikimori-auth"

        private const val CLIENT_ID = "PB9dq8DzI405s7wdtwTdirYqHiyVMh--djnP7lBUqSA"
        private const val CLIENT_SECRET = "NajpZcOBKB9sJtgNcejf8OB9jBN1OYYoo-k4h2WWZus"

        fun authUrl(): Uri = LOGIN_URL.toUri().buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT_URL)
            .appendQueryParameter("response_type", "code")
            .build()

        fun refreshTokenRequest(token: String) = POST(
            OAUTH_URL,
            body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .add("refresh_token", token)
                .build(),
        )
    }
}
