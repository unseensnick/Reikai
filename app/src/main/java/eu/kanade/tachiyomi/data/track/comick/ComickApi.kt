package eu.kanade.tachiyomi.data.track.comick

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.comick.dto.ComickComicDetails
import eu.kanade.tachiyomi.data.track.comick.dto.ComickLibraryEntry
import eu.kanade.tachiyomi.data.track.comick.dto.ComickOAuth
import eu.kanade.tachiyomi.data.track.comick.dto.ComickSearchResult
import eu.kanade.tachiyomi.data.track.comick.dto.ComickTitleLookupResult
import eu.kanade.tachiyomi.data.track.comick.dto.ComickUser
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.PUT
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import reikai.data.track.newLoginPkce
import reikai.data.track.newLoginState
import tachiyomi.core.common.util.lang.withIOContext
import java.math.RoundingMode
import kotlin.time.Duration.Companion.minutes
import tachiyomi.domain.track.model.Track as DomainTrack

class ComickApi(
    private val trackerId: Long,
    baseClient: OkHttpClient,
    interceptor: ComickInterceptor,
    private val json: Json,
) {

    private val client = baseClient.newBuilder()
        .rateLimit(permits = 55, period = 1.minutes)
        .addInterceptor {
            it.request().newBuilder()
                .header(
                    "User-Agent",
                    buildString {
                        append("Reikai/v${BuildConfig.VERSION_NAME} ") // RK: identifies as Reikai
                        // RK: upstream has a stray ")" after the application id
                        append("(${BuildConfig.APPLICATION_ID} ${BuildConfig.COMMIT_SHA}) ")
                        append("(Android) (https://github.com/mihonapp/mihon)")
                    },
                )
                .header("Accept", jsonMime.toString())
                .build()
                .let(it::proceed)
        }.build()

    private val authClient = client.newBuilder()
        .addInterceptor(interceptor)
        .build()

    suspend fun addLibManga(track: Track): Track {
        return withIOContext {
            val url = "$RESOURCE_URL/me/library".toUri().buildUpon()
                .appendPath(track.remote_id.toHid())
                .build()

            val body = buildJsonObject {
                put("status", track.status.toApiListStatus())
                if (track.last_chapter_read > 0) {
                    putJsonObject("progress") {
                        put(
                            "number",
                            track.last_chapter_read
                                .toBigDecimal()
                                .setScale(2, RoundingMode.HALF_UP)
                                .stripTrailingZeros()
                                .toPlainString(),
                        )
                    }
                } else {
                    put("progress", null)
                }
            }
                .toString()
                .toRequestBody(jsonMime)

            authClient
                .newCall(PUT(url.toString(), body = body))
                .awaitSuccess()
                .close()

            // only returns the same data back with nothing additionally useful
            track
        }
    }

    suspend fun deleteLibManga(track: DomainTrack) {
        withIOContext {
            val url = "$RESOURCE_URL/me/library".toUri().buildUpon()
                .appendPath(track.remoteId.toHid())
                .build()

            authClient
                .newCall(DELETE(url.toString(), body = RequestBody.EMPTY))
                .awaitSuccess()
                .close()
        }
    }

    suspend fun findLibManga(track: Track): Track? {
        return withIOContext {
            val url = "$RESOURCE_URL/me/library".toUri().buildUpon()
                .appendPath(track.remote_id.toHid())
                .build()
            with(json) {
                try {
                    authClient.newCall(GET(url.toString()))
                        .awaitSuccess()
                        .parseAs<ComickLibraryEntry>()
                        .toTrack(trackerId)
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        return@with null
                    }
                    throw e
                }
            }
        }
    }

    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val url = "$RESOURCE_URL/me/library".toUri().buildUpon()
                .appendPath(track.remote_id.toHid())
                .build()

            val body = buildJsonObject {
                put("rating", track.score.toInt().takeIf { it > 0 })
                put("status", track.status.toApiListStatus())
                putJsonObject("progress") {
                    put(
                        "number",
                        track.last_chapter_read
                            .toBigDecimal()
                            .setScale(2, RoundingMode.HALF_UP)
                            .stripTrailingZeros()
                            .toPlainString(),
                    )
                }
            }
                .toString()
                .toRequestBody(jsonMime)

            val request = Request.Builder()
                .url(url.toString())
                .patch(body)
                .build()

            authClient.newCall(request)
                .awaitSuccess()
                .close()

            // only returns the same data back with nothing additionally useful
            track
        }
    }

    suspend fun search(search: String): List<TrackSearch> {
        return withIOContext {
            val url = "$RESOURCE_URL/titles".toUri().buildUpon()
                // Comick throws a 400 when the query is over 100 characters
                .appendQueryParameter("q", search.take(100))
                .appendQueryParameter("media_type", "manga")
                .appendQueryParameter("limit", "20")
                .build()

            with(json) {
                authClient.newCall(GET(url.toString()))
                    .awaitSuccess()
                    .parseAs<ComickSearchResult>()
                    .data
                    .map { it.toTrackSearch(trackerId) }
            }
        }
    }

    suspend fun getMangaDetails(hid: String): TrackSearch? {
        return withIOContext {
            val url = "$RESOURCE_URL/titles".toUri().buildUpon()
                .appendPath(hid)
                .build()

            with(json) {
                try {
                    authClient.newCall(GET(url.toString()))
                        .awaitSuccess()
                        .parseAs<ComickTitleLookupResult>()
                        .data
                        .toTrackSearch(trackerId)
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        return@with null
                    }
                    throw e
                }
            }
        }
    }

    // RK --> creators for autofill: public, so the plain client, and a missing page is no creators
    suspend fun getComicDetails(hid: String): ComickComicDetails? {
        return withIOContext {
            val url = "$API_BASE_URL/v1.0/comic/".toUri().buildUpon()
                .appendPath(hid)
                .build()

            with(json) {
                try {
                    client.newCall(GET(url.toString()))
                        .awaitSuccess()
                        .parseAs<ComickComicDetails>()
                } catch (e: HttpException) {
                    if (e.code == 404) {
                        return@with null
                    }
                    throw e
                }
            }
        }
    }
    // RK <--

    suspend fun getCurrentUser(): ComickUser {
        return withIOContext {
            val url = "$RESOURCE_URL/me"

            with(json) {
                authClient.newCall(GET(url))
                    .awaitSuccess()
                    .parseAs<ComickUser>()
            }
        }
    }

    /**
     * @throws ComickMissingScopesException Thrown when user does not grant offline_access or library:write
     */
    suspend fun getAccessToken(code: String, codeVerifier: String): ComickOAuth { // RK: stored verifier
        return withIOContext {
            val formBody = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("client_id", CLIENT_ID)
                .add("code", code)
                .add("redirect_uri", REDIRECT_URI)
                .add("code_verifier", codeVerifier)
                .add("resource", RESOURCE_URL)
                .build()

            with(json) {
                val response = client.newCall(
                    POST("${OAUTH_URL}/token", body = formBody),
                )
                    .awaitSuccess()

                val body = response.body.string()
                if (!body.contains("refresh_token")) {
                    throw ComickMissingScopesException("Permission for ongoing synchronisation is required.")
                }

                val oauth = json.decodeFromString<ComickOAuth>(body)
                if (!oauth.scope.contains(LIBRARY_WRITE_SCOPE)) {
                    // Users may forget to enable this since it's off by default
                    throw ComickMissingScopesException("Permission to add/remove manga from your library is required.")
                }

                oauth
            }
        }
    }

    // RK: verifyOAuthState moved to Comick.verifyOAuthState, over the stored state

    companion object {
        private const val CLIENT_ID = "SGrUojDvbgqfhSCQbwfCjUPIccYMJdBH"
        private const val REDIRECT_URI = "app.mihon://comick-auth"

        private const val LIBRARY_WRITE_SCOPE = "library:write"
        private const val SCOPES = "library:read $LIBRARY_WRITE_SCOPE offline_access"

        private const val OAUTH_URL = "https://comick.dev/api/auth/oauth2"
        private const val API_BASE_URL = "https://api.comick.dev"
        private const val RESOURCE_URL = "$API_BASE_URL/integrations/v1"

        // RK --> the verifier and state are stored rather than held here (TrackerLoginPkce.kt), so the
        // login survives the process being killed while the browser is open
        fun authUrl(trackPreferences: TrackPreferences, tracker: Comick): Uri =
            "$OAUTH_URL/authorize".toUri().buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", REDIRECT_URI)
                .appendQueryParameter("scope", SCOPES)
                .appendQueryParameter("resource", RESOURCE_URL)
                .appendQueryParameter("state", trackPreferences.newLoginState(tracker))
                .appendQueryParameter("code_challenge", trackPreferences.newLoginPkce(tracker).codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
        // RK <--

        fun refreshTokenRequest(refreshToken: String) = POST(
            "$OAUTH_URL/token",
            body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CLIENT_ID)
                .add("refresh_token", refreshToken)
                .add("resource", RESOURCE_URL)
                .build(),
            headers = headersOf(
                "Accept",
                jsonMime.toString(),
            ),
        )

        // RK: getOAuthStateParam and getPkceS256ChallengeCode replaced by TrackPreferences.newLoginState
        // and newLoginPkce, which store what they make
    }
}
