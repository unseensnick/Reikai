package eu.kanade.tachiyomi.data.track.kitsu

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.network.okHttpClient
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuLibraryEntry
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuOAuth
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuUser
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuUserLibraryNode
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuUserLibraryResult
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.dataOrElse
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import mihon.graphql.kitsu.KitsuAddLibMangaMutation
import mihon.graphql.kitsu.KitsuDeleteLibEntryMutation
import mihon.graphql.kitsu.KitsuFindLibMangaQuery
import mihon.graphql.kitsu.KitsuGetCurrentAccountQuery
import mihon.graphql.kitsu.KitsuGetMangaDetailsByIdQuery
import mihon.graphql.kitsu.KitsuGetMangaDetailsBySlugQuery
import mihon.graphql.kitsu.KitsuSearchMangaByTitleQuery
import mihon.graphql.kitsu.KitsuUpdateLibMangaMutation
import mihon.graphql.kitsu.ReikaiKitsuFindLibraryEntryQuery
import mihon.graphql.kitsu.ReikaiKitsuGetMangaMetadataQuery
import mihon.graphql.kitsu.fragment.MangaFragment
import mihon.graphql.kitsu.type.MangaSubtypeEnum
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import reikai.domain.track.KitsuEntryLookup
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import kotlin.time.Instant
import tachiyomi.domain.track.model.Track as DomainTrack

class KitsuApi(
    private val trackerId: Long,
    private val client: OkHttpClient,
    interceptor: KitsuInterceptor,
) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    private val graphQlClient by lazy {
        ApolloClient.Builder()
            .serverUrl("https://kitsu.app/api/graphql")
            .okHttpClient(authClient)
            .dispatcher(Dispatchers.IO)
            // required to log the error body in dataOrElse, which also properly closes it
            .httpExposeErrorBody(true)
            .build()
    }

    suspend fun addLibManga(track: Track): Track {
        return graphQlClient
            .mutation(
                KitsuAddLibMangaMutation(
                    media_id = track.remote_id.toString(),
                    status = track.toKitsuStatus(),
                    progress = track.last_chapter_read.toInt(),
                    private = track.private,
                    rating = Optional.present(track.score.toInt().takeIf { it > 0 }),
                ),
            )
            .execute()
            .dataOrElse(
                errorLog = "Kitsu: Failed to add manga",
                default = { null },
            ) {
                it.libraryEntry.create?.libraryEntry?.id?.let { libraryId ->
                    track.library_id = libraryId.toLong()
                    track
                }
            }
            ?: throw Exception("Failed to add manga")
    }

    suspend fun updateLibManga(track: Track): Track {
        val libraryId = track.library_id
        requireNotNull(libraryId) { "Kitsu cannot update track with null library_id" }

        return graphQlClient
            .mutation(
                KitsuUpdateLibMangaMutation(
                    library_id = libraryId.toString(),
                    status = track.toKitsuStatus(),
                    progress = track.last_chapter_read.toInt(),
                    private = track.private,
                    rating = Optional.present(track.score.toInt().takeIf { it > 0 }),
                    startedAt = Optional.present(
                        track.started_reading_date
                            .takeIf { it > 0 }
                            ?.let { Instant.fromEpochMilliseconds(it) },
                    ),
                    finishedAt = Optional.present(
                        track.finished_reading_date
                            .takeIf { it > 0 }
                            ?.let { Instant.fromEpochMilliseconds(it) },
                    ),
                ),
            )
            .execute()
            .dataOrElse(
                errorLog = "Kitsu: Failed to update manga",
                default = { null },
            ) {
                it.libraryEntry.update?.libraryEntry?.id?.let { libraryId ->
                    logcat { "Kitsu: Updated library entry $libraryId" }
                    track.library_id = libraryId.toLong()
                    track
                }
            }
            ?: throw Exception("Failed to update manga")
    }

    suspend fun removeLibManga(track: DomainTrack) {
        val libraryId = track.libraryId
        requireNotNull(libraryId) { "Kitsu cannot delete track with null library_id" }

        graphQlClient
            .mutation(
                KitsuDeleteLibEntryMutation(
                    library_id = libraryId.toString(),
                ),
            )
            .execute()
            .dataOrElse(
                errorLog = "Kitsu: Failed to delete manga",
                default = {},
                onException = { e ->
                    val body = e.body?.use { it.readUtf8() }
                    if (
                        e.statusCode == 500 &&
                        body?.contains("Couldn't find LibraryEntry with 'id'=") == true
                    ) {
                        // RK: upstream's comment, less the date the comment hook refuses
                        // Deleting something not in the library returns a 500 with a
                        // "Couldn't find LibraryEntry" error message at the error.message key --
                        // but the user gets their wish of "title not in library" so ignore it
                        return@dataOrElse
                    } else {
                        throw HttpException(e.statusCode).apply { stackTrace = e.stackTrace }
                    }
                },
            ) {
                logcat { "Kitsu: Deleted library entry ${it.libraryEntry.delete?.libraryEntry?.id}" }
            }
    }

    suspend fun search(search: String, novel: Boolean = false): List<TrackSearch> { // RK: novel subtype split
        return graphQlClient
            .query(
                KitsuSearchMangaByTitleQuery(
                    query = search,
                ),
            )
            .execute()
            .dataOrElse(
                errorLog = "Kitsu: Search failed",
                default = { emptyList() },
            ) {
                it.searchMangaByTitle.nodes
                    ?.filter { node -> node?.mangaFragment?.isNovel() == novel } // RK
                    ?.mapNotNull { node -> node?.toTrackSearch(trackerId) }
            }
            ?: emptyList()
    }

    suspend fun findLibManga(track: Track): Track? {
        return graphQlClient
            .query(
                KitsuFindLibMangaQuery(
                    remote_id = track.remote_id.toString(),
                ),
            )
            .execute()
            .dataOrElse(
                errorLog = "Kitsu: Failed to find manga in library",
                default = { null },
            ) {
                it.findMangaById?.toTrackSearch(trackerId)
            }
    }

    // RK --> a Yokai-era track's remote id read as a library entry id, with the viewer's own profile id
    suspend fun findLibraryEntry(entryId: Long): KitsuEntryLookup? {
        return graphQlClient
            .query(ReikaiKitsuFindLibraryEntryQuery(id = entryId.toString()))
            .execute()
            .dataOrElse(
                errorLog = "Kitsu: Failed to find library entry",
                default = { null },
                // A failed heal attempt leaves the row failing as it did before, not with a new error
                onException = {},
            ) { data ->
                data.findLibraryEntryById?.let { entry ->
                    KitsuEntryLookup(
                        ownerId = entry.user.id,
                        viewerId = data.currentProfile?.id,
                        mangaId = entry.media.onManga?.id?.toLongOrNull(),
                    )
                }
            }
    }
    // RK <--

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

    suspend fun getCurrentUser(): KitsuUser {
        return graphQlClient
            .query(
                KitsuGetCurrentAccountQuery(),
            )
            .execute()
            .dataOrElse(
                errorLog = "Kitsu: Failed to get current user",
                default = { null },
            ) {
                it.currentAccount?.toKitsuUser()
            }
            ?: throw Exception("Failed to get Kitsu user data")
    }

    suspend fun getMangaDetails(search: String, novel: Boolean = false): TrackSearch? { // RK: novel subtype split
        val isSearchById = search.matches(Regex("\\d+"))

        return if (isSearchById) {
            getMangaDetailsById(search, novel) // RK
        } else {
            getMangaDetailsBySlug(search, novel) // RK
        }
    }

    private suspend fun getMangaDetailsById(id: String, novel: Boolean): TrackSearch? { // RK
        return graphQlClient
            .query(
                KitsuGetMangaDetailsByIdQuery(
                    id = id,
                ),
            )
            .execute()
            .dataOrElse(
                errorLog = "Kitsu: Search by ID failed",
                default = { null },
            ) {
                it.findMangaById?.takeIf { manga -> manga.mangaFragment.isNovel() == novel } // RK
                    ?.toTrackSearch(trackerId)
            }
    }

    private suspend fun getMangaDetailsBySlug(slug: String, novel: Boolean): TrackSearch? { // RK
        return graphQlClient
            .query(
                KitsuGetMangaDetailsBySlugQuery(
                    slug = slug,
                ),
            )
            .execute()
            .dataOrElse(
                errorLog = "Kitsu: Search by Slug failed",
                default = { null },
            ) {
                it.findMangaBySlug?.takeIf { manga -> manga.mangaFragment.isNovel() == novel } // RK
                    ?.toTrackSearch(trackerId)
            }
    }

    // RK --> full library pull for the recommendation taste profile. Upstream selects nothing like
    // this, so there is no counterpart to sync against. Stays on raw JSON: its DTOs are pinned by the
    // taste-profile tests.
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
            tags = media.categories.nodes.mapNotNull { it.title.localized() },
            malId = externalIds[MAL_MAPPING_SITE]?.toLongOrNull(),
            anilistId = externalIds[ANILIST_MAPPING_SITE]?.toLongOrNull(),
        )
    }

    // "Fill from tracker" metadata. Its own operation rather than upstream's fragment, which caps staff
    // at five and selects no categories. Kitsu returns its NSFW categories only to an account whose own
    // SFW filter is off, so on a default account this genre list is short.
    suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        val manga = graphQlClient
            .query(ReikaiKitsuGetMangaMetadataQuery(id = track.remoteId.toString()))
            .execute()
            .dataOrElse(
                errorLog = "Kitsu: Failed to get manga metadata",
                default = { null },
            ) { it.findMangaById }
            ?: return TrackMangaMetadata()

        // Kitsu spells credits as free-form role strings, so the match is a substring, not equality.
        fun credits(roleMatch: String) = manga.staff.nodes.orEmpty()
            .filterNotNull()
            .filter { roleMatch in it.role }
            .map { it.person.name }
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifEmpty { null }

        return TrackMangaMetadata(
            remoteId = manga.id.toLongOrNull(),
            title = manga.titles.preferred,
            thumbnailUrl = manga.posterImage?.original?.url,
            description = (manga.description["en"] as? String)?.ifBlank { null },
            authors = credits("Story"),
            artists = credits("Art"),
            genres = manga.categories.nodes.orEmpty()
                .mapNotNull { node -> node?.title?.localized() }
                .takeIf { it.isNotEmpty() },
        )
    }

    /** Prefers English, but takes whatever locale the entry has rather than dropping the tag. */
    private fun Map<String, Any?>.localized(): String? =
        ((this["en"] ?: values.firstOrNull()) as? String)?.takeIf { it.isNotBlank() }
    // RK <--

    companion object {
        private const val CLIENT_ID = "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd"
        private const val CLIENT_SECRET = "54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151"

        private const val LOGIN_URL = "https://kitsu.app/api/oauth/token"

        // RK --> the raw library pull, and the external sites whose ids the taste profile resolves
        private const val GRAPHQL_API_URL = "https://kitsu.app/api/graphql"
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
    }
}

// RK: Kitsu files light novels under its manga type, separated only by subtype, so both search paths
// answer "is this a novel" here rather than each spelling out the comparison.
private fun MangaFragment.isNovel(): Boolean = subtype == MangaSubtypeEnum.NOVEL
