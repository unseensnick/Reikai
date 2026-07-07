package eu.kanade.tachiyomi.data.track.anilist

import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.anilist.dto.ALAddMangaResult
import eu.kanade.tachiyomi.data.track.anilist.dto.ALCurrentUserResult
import eu.kanade.tachiyomi.data.track.anilist.dto.ALLibraryEntry
import eu.kanade.tachiyomi.data.track.anilist.dto.ALOAuth
import eu.kanade.tachiyomi.data.track.anilist.dto.ALUserLibraryResult
import eu.kanade.tachiyomi.data.track.anilist.dto.ALSearchResult
import eu.kanade.tachiyomi.data.track.anilist.dto.ALUserListMangaQueryResult
import eu.kanade.tachiyomi.data.track.anilist.dto.ALUserViewerData
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.minutes
import tachiyomi.domain.track.model.Track as DomainTrack

class AnilistApi(val client: OkHttpClient, interceptor: AnilistInterceptor) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder()
        .addInterceptor(interceptor)
        .rateLimit(permits = 85, period = 1.minutes)
        .build()

    suspend fun addLibManga(track: Track): Track {
        return withIOContext {
            val query = $$"""
            |mutation AddManga($mangaId: Int, $progress: Int, $status: MediaListStatus, $private: Boolean) {
                |SaveMediaListEntry (mediaId: $mangaId, progress: $progress, status: $status, private: $private) {
                |   id
                |   status
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("mangaId", track.remote_id)
                    put("progress", track.last_chapter_read.toInt())
                    put("status", track.toApiStatus())
                    put("private", track.private)
                }
            }
            with(json) {
                authClient.newCall(
                    POST(
                        API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<ALAddMangaResult>()
                    .let {
                        track.library_id = it.data.entry.id
                        track
                    }
            }
        }
    }

    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val query = $$"""
            |mutation UpdateManga(
                |$listId: Int, $progress: Int, $status: MediaListStatus, $private: Boolean,
                |$score: Int, $startedAt: FuzzyDateInput, $completedAt: FuzzyDateInput
            |) {
                |SaveMediaListEntry(
                    |id: $listId, progress: $progress, status: $status, private: $private,
                    |scoreRaw: $score, startedAt: $startedAt, completedAt: $completedAt
                |) {
                    |id
                    |status
                    |progress
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("listId", track.library_id)
                    put("progress", track.last_chapter_read.toInt())
                    put("status", track.toApiStatus())
                    put("score", track.score.toInt())
                    put("startedAt", createDate(track.started_reading_date))
                    put("completedAt", createDate(track.finished_reading_date))
                    put("private", track.private)
                }
            }
            authClient.newCall(POST(API_URL, body = payload.toString().toRequestBody(jsonMime)))
                .awaitSuccess()
            track
        }
    }

    suspend fun deleteLibManga(track: DomainTrack) {
        withIOContext {
            val query = $$"""
            |mutation DeleteManga($listId: Int) {
                |DeleteMediaListEntry(id: $listId) {
                    |deleted
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("listId", track.libraryId)
                }
            }
            authClient.newCall(POST(API_URL, body = payload.toString().toRequestBody(jsonMime)))
                .awaitSuccess()
        }
    }

    suspend fun search(search: String, novel: Boolean = false): List<TrackSearch> {
        return withIOContext {
            // RK --> light novels are type: MANGA with format: NOVEL, which manga search excludes (Active #8)
            val formatFilter = if (novel) "format: NOVEL" else "format_not_in: [NOVEL]"
            // RK <--
            val query = $$"""
            |query Search($query: String) {
                |Page (perPage: 50) {
                    |media(search: $query, type: MANGA, $${formatFilter}) {
                        |id
                        |staff {
                            |edges {
                                |role
                                |id
                                |node {
                                    |name {
                                        |full
                                        |userPreferred
                                        |native
                                    |}
                                |}
                            |}
                        |}
                        |title {
                            |userPreferred
                        |}
                        |coverImage {
                            |large
                        |}
                        |format
                        |countryOfOrigin
                        |status
                        |chapters
                        |description
                        |startDate {
                            |year
                            |month
                            |day
                        |}
                        |averageScore
                    |}
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("query", search)
                }
            }
            with(json) {
                authClient.newCall(
                    POST(
                        API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<ALSearchResult>()
                    .data.page.media
                    .map { it.toALManga().toTrack() }
            }
        }
    }

    suspend fun findLibManga(track: Track, userid: Int): Track? {
        return withIOContext {
            val query = $$"""
            |query ($id: Int!, $manga_id: Int!) {
                |Page {
                    |mediaList(userId: $id, type: MANGA, mediaId: $manga_id) {
                        |id
                        |status
                        |scoreRaw: score(format: POINT_100)
                        |progress
                        |private
                        |startedAt {
                            |year
                            |month
                            |day
                        |}
                        |completedAt {
                            |year
                            |month
                            |day
                        |}
                        |media {
                            |id
                            |title {
                                |userPreferred
                            |}
                            |coverImage {
                                |large
                            |}
                            |format
                            |status
                            |chapters
                            |description
                            |startDate {
                                |year
                                |month
                                |day
                            |}
                            |staff {
                                |edges {
                                    |role
                                    |id
                                    |node {
                                        |name {
                                            |full
                                            |userPreferred
                                            |native
                                        |}
                                    |}
                                |}
                            |}
                        |}
                    |}
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("id", userid)
                    put("manga_id", track.remote_id)
                }
            }
            with(json) {
                authClient.newCall(
                    POST(
                        API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<ALUserListMangaQueryResult>()
                    .data.page.mediaList
                    .map { it.toALUserManga() }
                    .firstOrNull()
                    ?.toTrack()
            }
        }
    }

    suspend fun getLibManga(track: Track, userId: Int): Track {
        return findLibManga(track, userId) ?: throw Exception("Could not find manga")
    }

    fun createOAuth(token: String): ALOAuth {
        return ALOAuth(token, "Bearer", System.currentTimeMillis() + 31536000000, 31536000000)
    }

    suspend fun getCurrentUser(): ALUserViewerData {
        return withIOContext {
            val query = """
            |query User {
                |Viewer {
                    |id
                    |name
                    |mediaListOptions {
                        |scoreFormat
                    |}
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
            }
            with(json) {
                authClient.newCall(
                    POST(
                        API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<ALCurrentUserResult>()
                    .data.viewer
            }
        }
    }

    // RK --> full library pull for the recommendation taste profile (one MediaListCollection call,
    // genres + tag names inline; scoreRaw as POINT_100 regardless of the user's display format).
    suspend fun getUserLibrary(userId: Int): List<ALLibraryEntry> {
        return withIOContext {
            val query = $$"""
            |query UserLibrary($userId: Int!) {
                |MediaListCollection(userId: $userId, type: MANGA) {
                    |lists {
                        |entries {
                            |status
                            |scoreRaw: score(format: POINT_100)
                            |media {
                                |id
                                |idMal
                                |title { userPreferred }
                                |genres
                                |tags { name }
                            |}
                        |}
                    |}
                |}
            |}
            |
            """.trimMargin()
            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("userId", userId)
                }
            }
            with(json) {
                authClient.newCall(POST(API_URL, body = payload.toString().toRequestBody(jsonMime)))
                    .awaitSuccess()
                    .parseAs<ALUserLibraryResult>()
                    .data.mediaListCollection.lists
                    .flatMap { it.entries }
            }
        }
    }
    // RK <--

    private fun createDate(dateValue: Long): JsonObject {
        if (dateValue == 0L) {
            return buildJsonObject {
                put("year", JsonNull)
                put("month", JsonNull)
                put("day", JsonNull)
            }
        }

        val dateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(dateValue), ZoneId.systemDefault())
        return buildJsonObject {
            put("year", dateTime.year)
            put("month", dateTime.monthValue)
            put("day", dateTime.dayOfMonth)
        }
    }

    companion object {
        private const val CLIENT_ID = "16329"
        private const val API_URL = "https://graphql.anilist.co/"
        private const val BASE_URL = "https://anilist.co/api/v2/"
        private const val BASE_MANGA_URL = "https://anilist.co/manga/"

        fun mangaUrl(mediaId: Long): String {
            return BASE_MANGA_URL + mediaId
        }

        fun authUrl(): Uri = "${BASE_URL}oauth/authorize".toUri().buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "token")
            .build()
    }
}
