package eu.kanade.tachiyomi.data.track.kitsu

import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuAccount
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuAddMangaResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuCategoryNode
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuCurrentAccountResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuDeleteMangaResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuLibraryEntry
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuManga
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuMetadataResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuOAuth
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuSearchByIdResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuSearchByIdWithLibraryResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuSearchBySlugResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuSearchByTitleResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuUpdateMangaResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuUserLibraryNode
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuUserLibraryResult
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import kotlin.time.Instant
import tachiyomi.domain.track.model.Track as DomainTrack

class KitsuApi(
    private val trackId: Long,
    private val client: OkHttpClient,
    interceptor: KitsuInterceptor,
) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    suspend fun addLibManga(track: Track): Track {
        return withIOContext {
            val query = $$"""
                |mutation AddManga(
                  |$media_id: ID!
                  |$status: LibraryEntryStatusEnum!
                  |$progress: Int!
                  |$private: Boolean!
                  |$rating: Int
                |) {
                  |libraryEntry {
                    |create(
                      |input: {
                        |mediaId: $media_id
                        |mediaType: MANGA
                        |status: $status
                        |progress: $progress
                        |private: $private
                        |rating: $rating
                      |}
                    |) {
                      |errors {
                        |message
                      |}
                      |libraryEntry {
                        |id
                      |}
                    |}
                  |}
                |}
            """.trimMargin()

            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("media_id", track.remote_id)
                    put("status", track.toKitsuApiStatus())
                    put("progress", track.last_chapter_read.toInt())
                    put("private", track.private)
                    put("rating", track.score.toInt().takeIf { it > 0 })
                }
            }

            with(json) {
                val parsed = authClient.newCall(
                    POST(
                        GRAPHQL_API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<KitsuAddMangaResult>()

                if (parsed.error != null) {
                    logcat(LogPriority.ERROR) { "Failed to add: ${parsed.error.message ?: "(none)"}" }
                    throw Exception("Failed to add manga")
                } else if (parsed.errors != null) {
                    parsed.errors.forEach {
                        logcat(LogPriority.ERROR) { "Failed to add: ${it.message ?: "(none)"}" }
                    }
                    throw Exception("Failed to add manga")
                } else if (parsed.data == null) {
                    logcat(LogPriority.ERROR) { "Kitsu error, errors, and data null?" }
                    throw Exception("Encountered unexpected error while adding manga")
                }

                parsed.data.libraryEntry.create.libraryEntry.id.let {
                    track.library_id = it.toLong()
                    track
                }
            }
        }
    }

    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val query = $$"""
                |mutation UpdateManga(
                  |$library_id: ID!
                  |$status: LibraryEntryStatusEnum!
                  |$progress: Int!
                  |$private: Boolean!
                  |$rating: Int
                  |$startedAt: ISO8601DateTime
                  |$finishedAt: ISO8601DateTime
                |) {
                  |libraryEntry {
                    |update(
                      |input: {
                        |id: $library_id
                        |status: $status
                        |progress: $progress
                        |private: $private
                        |rating: $rating
                        |startedAt: $startedAt
                        |finishedAt: $finishedAt
                      |}
                    |) {
                      |errors {
                        |message
                      |}
                      |libraryEntry {
                        |id
                      |}
                    |}
                  |}
                |}
            """.trimMargin()

            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("library_id", track.library_id)
                    put("status", track.toKitsuApiStatus())
                    put("progress", track.last_chapter_read.toInt())
                    put("private", track.private)
                    put("rating", track.score.toInt().takeIf { it > 0 })
                    put(
                        "startedAt",
                        track.started_reading_date
                            .takeIf { it > 0 }
                            ?.let { Instant.fromEpochMilliseconds(it).toString() },
                    )
                    put(
                        "finishedAt",
                        track.finished_reading_date
                            .takeIf { it > 0 }
                            ?.let { Instant.fromEpochMilliseconds(it).toString() },
                    )
                }
            }

            with(json) {
                val parsed = authClient.newCall(
                    POST(
                        GRAPHQL_API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<KitsuUpdateMangaResult>()

                if (parsed.error != null) {
                    logcat(LogPriority.ERROR) { "Failed to update: ${parsed.error.message ?: "(none)"}" }
                    throw Exception("Failed to update manga")
                } else if (parsed.errors != null) {
                    parsed.errors.forEach {
                        logcat(LogPriority.ERROR) { "Failed to update: ${it.message ?: "(none)"}" }
                    }
                    throw Exception("Failed to update manga")
                } else if (parsed.data == null) {
                    logcat(LogPriority.ERROR) { "Kitsu error, errors, and data null?" }
                    throw Exception("Encountered unexpected error while updating manga")
                }

                track
            }
        }
    }

    suspend fun removeLibManga(track: DomainTrack) {
        withIOContext {
            val query = $$"""|
                |mutation DeleteLibEntry(
                  |$library_id: ID!
                |) {
                  |libraryEntry {
                    |delete(
                      |input: {
                        |id: $library_id
                      |}
                    |) {
                      |errors {
                        |message
                      |}
                      |libraryEntry {
                        |id
                      |}
                    |}
                  |}
                |}
            """.trimMargin()

            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("library_id", track.libraryId)
                }
            }

            with(json) {
                val parsed = authClient.newCall(
                    POST(
                        GRAPHQL_API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    // Deleting something not in the library returns a 500 with "Couldn't find LibraryEntry" msg
                    // awaitSuccess would throw with that but user gets their wish of "title not in library" so ignore it
                    .await()
                    .parseAs<KitsuDeleteMangaResult>()

                if (parsed.error != null) {
                    logcat(LogPriority.ERROR) { "Failed to delete: ${parsed.error.message ?: "(none)"}" }
                    if (parsed.error.message != null && parsed.error.message.startsWith("Couldn't find")) {
                        return@with
                    }
                    throw Exception("Failed to delete manga")
                } else if (parsed.errors != null) {
                    parsed.errors.forEach {
                        logcat(LogPriority.ERROR) { "Failed to delete: ${it.message ?: "(none)"}" }
                    }
                    throw Exception("Failed to delete manga")
                } else if (parsed.data == null) {
                    logcat(LogPriority.ERROR) { "Kitsu error, errors, and data null?" }
                    throw Exception("Encountered unexpected error while deleting manga")
                }
            }
        }
    }

    suspend fun search(search: String, novel: Boolean = false): List<TrackSearch> {
        return withIOContext {
            val query = $$"""
                |query Query($query: String!) {
                  |searchMangaByTitle(title: $query, first: 20) {
                    |nodes {
                      $$COMMON_MANGA_DATA
                    |}
                  |}
                |}
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
                        GRAPHQL_API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<KitsuSearchByTitleResult>()
                    .data.searchMangaByTitle.nodes
                    // RK --> Kitsu's manga type covers light novels via subtype. GraphQL returns the
                    // enum in upper case where the old REST API returned it lower, so compare loosely.
                    .filter { it.isNovel() == novel }
                    // RK <--
                    .map { it.toTrackSearch(trackId) }
            }
        }
    }

    suspend fun findLibManga(track: Track): Track? {
        return withIOContext {
            val query = $$"""
                |query Query($remote_id: ID!) {
                  |findMangaById(id: $remote_id) {
                    |$$COMMON_MANGA_DATA
                    |myLibraryEntry {
                      |id
                      |private
                      |progress
                      |rating
                      |reconsuming
                      |status
                      |startedAt
                      |finishedAt
                    |}
                  |}
                |}
            """.trimMargin()

            val payload = buildJsonObject {
                put("query", query)
                putJsonObject("variables") {
                    put("remote_id", track.remote_id)
                }
            }

            with(json) {
                authClient.newCall(
                    POST(
                        GRAPHQL_API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<KitsuSearchByIdWithLibraryResult>()
                    .data.findMangaById
                    ?.toTrackSearch(trackId)
            }
        }
    }

    suspend fun login(username: String, password: String): KitsuOAuth {
        return withIOContext {
            val formBody: RequestBody = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .add("grant_type", "password")
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build()
            with(json) {
                client.newCall(POST(LOGIN_URL, body = formBody))
                    .awaitSuccess()
                    .parseAs()
            }
        }
    }

    suspend fun getCurrentUser(): KitsuAccount {
        return withIOContext {
            val query = """
                |query Query {
                  |currentAccount {
                    |id
                    |ratingSystem
                    |profile {
                      |name
                    |}
                  |}
                |}
            """.trimMargin()

            val payload = buildJsonObject {
                put("query", query)
            }

            with(json) {
                authClient.newCall(
                    POST(
                        GRAPHQL_API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<KitsuCurrentAccountResult>()
                    .data.currentAccount
            }
        }
    }

    suspend fun getMangaDetails(search: String, novel: Boolean = false): TrackSearch? {
        val isSearchById = search.matches(Regex("\\d+"))

        val query = if (isSearchById) {
            $$"""
                |query Query($query: ID!) {
                  |findMangaById(id: $query) {
                    |$$COMMON_MANGA_DATA
                  |}
                |}
            """
        } else {
            $$"""
                |query Query($query: String!) {
                  |findMangaBySlug(slug: $query) {
                    |$$COMMON_MANGA_DATA
                  |}
                |}
            """
        }

        val payload = buildJsonObject {
            put("query", query.trimMargin())
            putJsonObject("variables") {
                put("query", search)
            }
        }

        return withIOContext {
            with(json) {
                val response = authClient.newCall(
                    POST(
                        GRAPHQL_API_URL,
                        body = payload.toString().toRequestBody(jsonMime),
                    ),
                )
                    .awaitSuccess()

                val kitsuManga = if (isSearchById) {
                    response
                        .parseAs<KitsuSearchByIdResult>()
                        .data.findMangaById
                } else {
                    response
                        .parseAs<KitsuSearchBySlugResult>()
                        .data.findMangaBySlug
                }

                // RK: the same subtype split the title search filters on.
                kitsuManga?.takeIf { it.isNovel() == novel }?.toTrackSearch(trackId)
            }
        }
    }

    // RK --> full library pull for the recommendation taste profile. Upstream selects nothing like
    // this, so there is no counterpart to sync against. `media` is the Media interface and every
    // field below lives on it, so no `... on Manga` fragment is needed.
    suspend fun getUserLibrary(): List<KitsuLibraryEntry> {
        val query = $$"""
            |query Query($cursor: String) {
              |currentProfile {
                |library {
                  |all(mediaType: MANGA, first: 500, after: $cursor) {
                    |pageInfo {
                      |hasNextPage
                      |endCursor
                    |}
                    |nodes {
                      |status
                      |rating
                      |media {
                        |id
                        |titles {
                          |preferred
                        |}
                        |categories(first: 100) {
                          |nodes {
                            |title(locales: ["en"])
                          |}
                        |}
                        |mappings(first: 50) {
                          |nodes {
                            |externalSite
                            |externalId
                          |}
                        |}
                      |}
                    |}
                  |}
                |}
              |}
            |}
        """.trimMargin()

        return withIOContext {
            val accumulated = mutableListOf<KitsuLibraryEntry>()
            var cursor: String? = null
            while (true) {
                val payload = buildJsonObject {
                    put("query", query)
                    putJsonObject("variables") {
                        put("cursor", cursor)
                    }
                }
                val connection = with(json) {
                    authClient.newCall(
                        POST(
                            GRAPHQL_API_URL,
                            body = payload.toString().toRequestBody(jsonMime),
                        ),
                    )
                        .awaitSuccess()
                        .parseAs<KitsuUserLibraryResult>()
                }.data.currentProfile?.library?.all ?: break

                accumulated += connection.nodes.mapNotNull { it.toLibraryEntry() }
                if (!connection.pageInfo.hasNextPage) break
                cursor = connection.pageInfo.endCursor ?: break
            }
            accumulated
        }
    }

    private fun KitsuUserLibraryNode.toLibraryEntry(): KitsuLibraryEntry? {
        val media = media ?: return null
        val mangaId = media.id.toLongOrNull() ?: return null
        val externalIds = media.mappings.nodes.associate { it.externalSite to it.externalId }
        return KitsuLibraryEntry(
            mangaId = mangaId,
            title = media.titles.preferred.orEmpty(),
            status = status,
            ratingTwenty = rating,
            tags = media.categories.nodes.mapNotNull { it.localizedTitle() },
            malId = externalIds[MAL_MAPPING_SITE]?.toLongOrNull(),
            anilistId = externalIds[ANILIST_MAPPING_SITE]?.toLongOrNull(),
        )
    }

    /** Prefers English, but takes whatever locale the entry has rather than dropping the tag. */
    private fun KitsuCategoryNode.localizedTitle(): String? =
        (title["en"] ?: title.values.firstOrNull())?.takeIf { it.isNotBlank() }

    // "Fill from tracker" metadata. Also still JSON:API: it needs the genre list, which upstream's
    // GraphQL selection set does not carry. Resolves staff (author/artist by role) and genres out of
    // the `included` graph.
    suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        return withIOContext {
            val url = "${JSON_API_BASE_URL}manga/${track.remoteId}".toUri().buildUpon()
                .encodedQuery("include=staff.person,categories")
                .build()
                .toString()
            val result = with(json) {
                authClient.newCall(GET(url)).awaitSuccess().parseAs<KitsuMetadataResult>()
            }
            val manga = result.data
            val attrs = manga.attributes

            val peopleById = result.included
                .filter { it.type == "people" }
                .associate { it.id to it.attributes.name.orEmpty() }
            val staffById = result.included.filter { it.type == "mediaStaff" }.associateBy { it.id }
            val categoryTitleById = result.included
                .filter { it.type == "categories" && it.attributes.nsfw != true }
                .associate { it.id to it.attributes.title.orEmpty() }

            fun staffNames(roleMatch: String): String? =
                manga.relationships.staff?.data.orEmpty()
                    .mapNotNull { staffById[it.id] }
                    .filter { roleMatch in it.attributes.role.orEmpty() }
                    .mapNotNull { it.relationships.person?.data?.id?.let(peopleById::get) }
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                    .ifEmpty { null }

            TrackMangaMetadata(
                remoteId = manga.id,
                title = attrs.canonicalTitle,
                thumbnailUrl = attrs.posterImage?.run { original ?: large ?: medium },
                description = attrs.synopsis?.ifBlank { null } ?: attrs.description?.ifBlank { null },
                authors = staffNames("Story"),
                artists = staffNames("Art"),
                genres = manga.relationships.categories?.data.orEmpty()
                    .mapNotNull { categoryTitleById[it.id] }
                    .filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() },
            )
        }
    }
    // RK <--

    companion object {
        private const val CLIENT_ID = "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd"
        private const val CLIENT_SECRET = "54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151"

        private const val GRAPHQL_API_URL = "https://kitsu.app/api/graphql"
        private const val LOGIN_URL = "https://kitsu.app/api/oauth/token"

        // RK --> the JSON:API endpoint the two islands above still use, and the external-site slugs
        // for resolving cross-tracker ids out of a manga's mappings.
        private const val JSON_API_BASE_URL = "https://kitsu.app/api/edge/"

        // Mapping external sites, named by GraphQL enum rather than by their JSON:API string value.
        private const val MAL_MAPPING_SITE = "MYANIMELIST_MANGA"
        private const val ANILIST_MAPPING_SITE = "ANILIST_MANGA"
        // RK <--

        fun refreshTokenRequest(token: String) = POST(
            LOGIN_URL,
            body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", token)
                .add("client_id", CLIENT_ID)
                .add("client_secret", CLIENT_SECRET)
                .build(),
        )

        private val COMMON_MANGA_DATA = """
            |id
            |titles {
              |preferred
            |}
            |chapterCount
            |staff(first: 5) {
              |nodes {
                |role
                |person {
                  |name
                |}
              |}
            |}
            |posterImage {
              |views(names: "small") {
                |name
                |url
              |}
              |original {
                |name
                |url
              |}
            |}
            |description(locales: "en")
            |status
            |subtype
            |startDate
            |endDate
            |slug
            |averageRating
        """.trimMargin()
    }
}

// RK: Kitsu files light novels under its manga type, separated only by subtype, so both search paths
// answer "is this a novel" here rather than each spelling out the comparison.
private fun KitsuManga.isNovel(): Boolean = subtype.equals("novel", ignoreCase = true)
