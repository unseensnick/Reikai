package eu.kanade.tachiyomi.data.track.anilist

import android.net.Uri
import androidx.core.net.toUri
import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.network.okHttpClient
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.anilist.dto.ALLibraryEntry
import eu.kanade.tachiyomi.data.track.anilist.dto.ALUser
import eu.kanade.tachiyomi.data.track.anilist.dto.ALUserLibraryResult
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.dataOrElse
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.lang.htmlDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mihon.graphql.anilist.AniListAddMangaMutation
import mihon.graphql.anilist.AniListDeleteMangaMutation
import mihon.graphql.anilist.AniListGetCurrentUserQuery
import mihon.graphql.anilist.AniListGetLibMangaQuery
import mihon.graphql.anilist.AniListGetMangaDetailsQuery
import mihon.graphql.anilist.AniListSearchMangaQuery
import mihon.graphql.anilist.AniListUpdateMangaMutation
import mihon.graphql.anilist.ReikaiAniListGetMangaMetadataQuery
import mihon.graphql.anilist.ReikaiAniListGetNovelDetailsQuery
import mihon.graphql.anilist.ReikaiAniListSearchNovelQuery
import mihon.graphql.anilist.type.FuzzyDateInput
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import tachiyomi.domain.track.model.Track as DomainTrack

class AnilistApi(
    val trackerId: Long,
    val client: OkHttpClient,
    interceptor: AnilistInterceptor,
) {

    // RK: the library pull stays on raw JSON (ALLibrary.kt)
    private val json: Json by injectLazy()

    private val authClient = client.newBuilder()
        .addInterceptor(interceptor)
        .rateLimit(permits = 25, period = 1.minutes)
        .build()

    private val graphQlClient by lazy {
        ApolloClient.Builder()
            .serverUrl("https://graphql.anilist.co")
            .okHttpClient(authClient)
            .dispatcher(Dispatchers.IO)
            // required to log the error body in dataOrElse, which also properly closes it
            .httpExposeErrorBody(true)
            .build()
    }

    suspend fun addLibManga(track: Track): Track {
        return graphQlClient
            .mutation(
                AniListAddMangaMutation(
                    manga_id = track.remote_id.toInt(),
                    progress = track.last_chapter_read.toInt(),
                    status = track.toApiStatus(),
                    private = track.private,
                ),
            )
            .execute()
            .throwOnAniListError() // RK
            .dataOrElse(
                errorLog = "AniList: Failed to add manga",
                default = { null },
            ) {
                it.SaveMediaListEntry?.id?.let { libraryId ->
                    track.library_id = libraryId.toLong()
                    track
                }
            }
            ?: throw Exception("Failed to add manga")
    }

    suspend fun updateLibManga(track: Track): Track {
        val libraryId = track.library_id
        requireNotNull(libraryId) { "AniList cannot update track with null library_id" }

        return graphQlClient
            .mutation(
                AniListUpdateMangaMutation(
                    library_id = libraryId.toInt(),
                    progress = track.last_chapter_read.toInt(),
                    status = track.toApiStatus(),
                    private = track.private,
                    score = track.score.toInt(),
                    startedAt = createFuzzyDate(track.started_reading_date),
                    completedAt = createFuzzyDate(track.finished_reading_date),
                ),
            )
            .execute()
            .throwOnAniListError() // RK
            .dataOrElse(
                errorLog = "AniList: Failed to update manga",
                default = { null },
            ) {
                it.SaveMediaListEntry?.id?.let { remoteLibraryId ->
                    track.library_id = remoteLibraryId.toLong()
                    track
                }
            }
            ?: throw Exception("Failed to update manga")
    }

    suspend fun deleteLibManga(track: DomainTrack) {
        val libraryId = track.libraryId
        requireNotNull(libraryId) { "AniList cannot delete track with null library_id" }

        graphQlClient
            .mutation(
                AniListDeleteMangaMutation(library_id = libraryId.toInt()),
            )
            .execute()
            .throwOnAniListError() // RK
            .dataOrElse(
                errorLog = "AniList: Failed to delete manga",
                default = { null },
            ) {
                it.DeleteMediaListEntry?.deleted?.let { deleted ->
                    if (deleted) {
                        logcat { "AniList: Deleted manga ${track.libraryId} successfully" }
                    }
                }
            }
            ?: throw Exception("Failed to delete manga")
    }

    // RK: novel picks Reikai's novel operation, since upstream's query hardcodes the novel exclusion
    suspend fun search(search: String, novel: Boolean = false): List<TrackSearch> {
        // RK -->
        if (novel) {
            return graphQlClient
                .query(ReikaiAniListSearchNovelQuery(search = search))
                .execute()
                .throwOnAniListError()
                .dataOrElse(
                    errorLog = "AniList: Novel search failed",
                    default = { emptyList() },
                ) {
                    it.Page?.media
                        ?.mapNotNull { alNovel -> alNovel?.toTrackSearch(trackerId) }
                        ?: emptyList()
                }
        }
        // RK <--
        return graphQlClient
            .query(
                AniListSearchMangaQuery(search = search),
            )
            .execute()
            .throwOnAniListError() // RK
            .dataOrElse(
                errorLog = "AniList: Search failed",
                default = { emptyList() },
            ) {
                it.Page?.media
                    ?.mapNotNull { alManga -> alManga?.toTrackSearch(trackerId) }
                    ?: emptyList()
            }
    }

    suspend fun findLibManga(track: Track, userId: Int): Track? {
        return graphQlClient
            .query(
                AniListGetLibMangaQuery(
                    user_id = userId,
                    manga_id = track.remote_id.toInt(),
                ),
            )
            .execute()
            .throwOnAniListError() // RK
            .dataOrElse(
                errorLog = "AniList: Failed to find manga in library",
                default = { null },
            ) {
                it.Page?.mediaList
                    ?.firstOrNull()
                    ?.toTrack(trackerId)
            }
    }

    suspend fun getCurrentUser(): ALUser {
        return graphQlClient
            .query(AniListGetCurrentUserQuery())
            .execute()
            .throwOnAniListError() // RK
            .dataOrElse(
                errorLog = "AniList: Failed to get current user",
                default = { null },
            ) {
                it.Viewer?.toALUser()
            }
            ?: throw Exception("Failed to get AniList user data")
    }

    // RK: novel picks Reikai's novel operation, as search does
    suspend fun getMangaDetails(id: Int, novel: Boolean = false): TrackSearch? {
        // RK -->
        if (novel) {
            return graphQlClient
                .query(ReikaiAniListGetNovelDetailsQuery(manga_id = id))
                .execute()
                .throwOnAniListError()
                .dataOrElse(
                    errorLog = "AniList: Failed to get novel details",
                    default = { null },
                ) {
                    it.Page?.media
                        ?.firstOrNull()
                        ?.toTrackSearch(trackerId)
                }
        }
        // RK <--
        return graphQlClient
            .query(
                AniListGetMangaDetailsQuery(manga_id = id),
            )
            .execute()
            .throwOnAniListError() // RK
            .dataOrElse(
                errorLog = "AniList: Failed to get manga details",
                default = { null },
            ) {
                it.Page?.media
                    ?.firstOrNull()
                    ?.toTrackSearch(trackerId)
            }
    }

    // RK --> metadata for the "Fill from tracker" editor action. Ported from Komikku, plus genres.
    suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        val media = graphQlClient
            .query(ReikaiAniListGetMangaMetadataQuery(manga_id = track.remoteId.toInt()))
            .execute()
            .throwOnAniListError()
            .dataOrElse(
                errorLog = "AniList: Failed to get manga metadata",
                default = { null },
            ) { it.Media }
            ?: throw Exception("Could not get metadata from AniList")
        fun credits(role: String) = media.staff?.edges.orEmpty()
            .filter { role in it?.role.orEmpty() }
            .mapNotNull { it?.node?.name?.let { name -> name.userPreferred ?: name.full ?: name.native } }
            .joinToString(", ")
            .ifEmpty { null }
        return TrackMangaMetadata(
            remoteId = media.id.toLong(),
            title = media.title?.userPreferred,
            thumbnailUrl = media.coverImage?.large,
            description = media.description?.htmlDecode()?.ifEmpty { null },
            authors = credits("Story"),
            artists = credits("Art"),
            genres = media.genres?.filterNotNull()?.takeIf { it.isNotEmpty() },
        )
    }

    // Full library pull for the recommendation taste profile (one MediaListCollection call, genres
    // and tag names inline; scoreRaw as POINT_100 regardless of the user's display format). Stays on
    // raw JSON: its DTOs are pinned by the taste-profile tests.
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

    private fun createFuzzyDate(dateValue: Long): FuzzyDateInput {
        // all absent/null
        if (dateValue == 0L) return FuzzyDateInput()

        val dateTime = Instant.fromEpochMilliseconds(dateValue).toLocalDateTime(TimeZone.currentSystemDefault())
        return FuzzyDateInput(
            year = Optional.present(dateTime.year),
            month = Optional.present(dateTime.month.number),
            day = Optional.present(dateTime.day),
        )
    }

    companion object {
        private const val CLIENT_ID = "16329"
        private const val API_URL = "https://graphql.anilist.co/" // RK: the raw library pull

        fun authUrl(): Uri = "https://anilist.co/api/v2/oauth/authorize".toUri().buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "token")
            .build()
    }
}
