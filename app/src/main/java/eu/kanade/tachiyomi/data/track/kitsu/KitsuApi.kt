package eu.kanade.tachiyomi.data.track.kitsu

import androidx.core.net.toUri
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuAddMangaResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuAlgoliaSearchResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuCurrentUserResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuLibraryEntry
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuLibraryResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuListSearchResult
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuOAuth
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuSearchResult
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.network.DELETE
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.util.system.withIOContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.FormBody
import okhttp3.Headers.Companion.headersOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy

class KitsuApi(private val client: OkHttpClient, interceptor: KitsuInterceptor) {

    private val json: Json by injectLazy()

    private val authClient = client.newBuilder().addInterceptor(interceptor).build()

    suspend fun addLibManga(track: Track, userId: String): Track {
        return withIOContext {
            val data = buildJsonObject {
                putJsonObject("data") {
                    put("type", "libraryEntries")
                    putJsonObject("attributes") {
                        put("status", track.toApiStatus())
                        put("progress", track.last_chapter_read.toInt())
                    }
                    putJsonObject("relationships") {
                        putJsonObject("user") {
                            putJsonObject("data") {
                                put("id", userId)
                                put("type", "users")
                            }
                        }
                        putJsonObject("media") {
                            putJsonObject("data") {
                                put("id", track.media_id)
                                put("type", "manga")
                            }
                        }
                    }
                }
            }

            with(json) {
                authClient.newCall(
                    POST(
                        "${BASE_URL}library-entries",
                        headers = headersOf("Content-Type", VND_API_JSON),
                        body = data.toString().toRequestBody(VND_JSON_MEDIA_TYPE),
                    ),
                )
                    .awaitSuccess()
                    .parseAs<KitsuAddMangaResult>()
                    .let {
                        track.media_id = it.data.id
                        track
                    }
            }
        }
    }

    suspend fun updateLibManga(track: Track): Track {
        return withIOContext {
            val data = buildJsonObject {
                putJsonObject("data") {
                    put("type", "libraryEntries")
                    put("id", track.media_id)
                    putJsonObject("attributes") {
                        put("status", track.toApiStatus())
                        val chapterCount = listOfNotNull(
                            track.total_chapters.takeIf { it > 0L },
                            track.last_chapter_read.toLong(),
                        )
                        put("progress", chapterCount.minOrNull())
                        put("ratingTwenty", track.toApiScore())
                        put("startedAt", KitsuDateHelper.convert(track.started_reading_date))
                        put("finishedAt", KitsuDateHelper.convert(track.finished_reading_date))
                    }
                }
            }

            authClient.newCall(
                Request.Builder()
                    .url("${BASE_URL}library-entries/${track.media_id}")
                    .headers(
                        headersOf("Content-Type", VND_API_JSON),
                    )
                    .patch(data.toString().toRequestBody(VND_JSON_MEDIA_TYPE))
                    .build(),
            )
                .awaitSuccess()

            track
        }
    }

    suspend fun remove(track: Track): Boolean {
        return withIOContext {
            authClient.newCall(
                DELETE(
                    "${BASE_URL}library-entries/${track.media_id}",
                    headers = headersOf("Content-Type", VND_API_JSON),
                ),
            )
                .awaitSuccess()
                .let {
                    true  // FIXME: Remove maybe?
                }
        }
    }

    suspend fun search(query: String): List<TrackSearch> {
        return withIOContext {
            authClient.newCall(GET(ALGOLIA_KEY_URL))
                .awaitSuccess()
                .parseAs<KitsuSearchResult>()
                .let {
                    algoliaSearch(it.media.key, query)
                }
        }
    }

    private suspend fun algoliaSearch(key: String, query: String): List<TrackSearch> {
        return withIOContext {
            val jsonObject = buildJsonObject {
                put("params", "query=$query$ALGOLIA_FILTER")
            }
            client.newCall(
                POST(
                    ALGOLIA_URL,
                    headers = headersOf(
                        "X-Algolia-Application-Id",
                        ALGOLIA_APP_ID,
                        "X-Algolia-API-Key",
                        key,
                    ),
                    body = jsonObject.toString().toRequestBody(jsonMime),
                ),
            )
                .awaitSuccess()
                .parseAs<KitsuAlgoliaSearchResult>()
                .hits
                .filter { it.subtype != "novel" }
                .map { it.toTrack() }
        }
    }

    suspend fun findLibManga(track: Track, userId: String): Track? {
        return withIOContext {
            val url = "${BASE_URL}library-entries".toUri().buildUpon()
                .encodedQuery("filter[manga_id]=${track.media_id}&filter[user_id]=$userId")
                .appendQueryParameter("include", "manga")
                .build()
            authClient.newCall(GET(url.toString()))
                .awaitSuccess()
                .parseAs<KitsuListSearchResult>()
                .let {
                    if (it.data.isNotEmpty() && it.included.isNotEmpty()) {
                        it.firstToTrack()
                    } else {
                        null
                    }
                }
        }
    }

    suspend fun getLibManga(track: Track): Track {
        return withIOContext {
            val url = "${BASE_URL}library-entries".toUri().buildUpon()
                .encodedQuery("filter[id]=${track.media_id}")
                .appendQueryParameter("include", "manga")
                .build()
            authClient.newCall(GET(url.toString()))
                .awaitSuccess()
                .parseAs<KitsuListSearchResult>()
                .let {
                    if (it.data.isNotEmpty() && it.included.isNotEmpty()) {
                        it.firstToTrack()
                    } else {
                        throw Exception("Could not find manga")
                    }
                }
        }
    }

    /**
     * Pulls every manga library entry for [userId], walking JSON:API's `links.next` until
     * exhausted. Resolves each page's `included[]` graph (manga + categories) into flat
     * [KitsuLibraryEntry] rows so the fetcher can stay simple.
     *
     * Field projection: `fields[libraryEntries]=status,ratingTwenty` + `fields[manga]=canonicalTitle`
     * + `fields[categories]=title` trims the payload to just what the taste profile needs.
     * Per JSON:API spec, `fields` doesn't affect relationship blocks, so manga.relationships.categories
     * is still emitted even though we only requested canonicalTitle on the manga attributes.
     */
    suspend fun getUserLibrary(userId: String): List<KitsuLibraryEntry> {
        return withIOContext {
            val accumulated = mutableListOf<KitsuLibraryEntry>()
            var nextUrl: String? = buildInitialLibraryUrl(userId)
            while (nextUrl != null) {
                val page = authClient.newCall(GET(nextUrl))
                    .awaitSuccess()
                    .parseAs<KitsuLibraryResult>()
                accumulated += resolveLibraryPage(page)
                nextUrl = page.links.next
            }
            accumulated
        }
    }

    private fun buildInitialLibraryUrl(userId: String): String {
        // Two Kitsu API quirks burnt into the comments here so the next maintainer doesn't
        // re-discover them the hard way:
        //
        // 1. No `filter[kind]=manga` — silently zeroes the response on this API revision.
        //    We rely on the resolver's `relationships.manga.data` check; anime entries don't
        //    populate that relationship and get dropped via mapNotNull.
        //
        // 2. No `fields[...]` sparse fieldsets — Kitsu treats them as "return ONLY these
        //    keys on the resource object", which strips the entire `relationships` block
        //    along with everything else not named. Without `relationships` we can't link
        //    library-entries to manga records. Bandwidth cost of dropping them is small
        //    (~few hundred KB for a typical library) and worth it.
        return "${BASE_URL}library-entries".toUri().buildUpon()
            .encodedQuery(
                "filter[user_id]=$userId" +
                    "&include=manga,manga.categories,manga.mappings" +
                    "&page[limit]=500",
            )
            .build()
            .toString()
    }

    private fun resolveLibraryPage(page: KitsuLibraryResult): List<KitsuLibraryEntry> {
        val mangaById = page.included
            .filter { it.type == "manga" }
            .associateBy { it.id }
        val categoryTitleById = page.included
            .filter { it.type == "categories" }
            .associate { it.id to (it.attributes.title.orEmpty()) }
        // Mappings records carry per-site external ids. We resolve two cross-tracker keys:
        // MAL (primary, when Kitsu has the mapping) and AniList (catches manhwa where MAL
        // mapping is absent). Other sites (AniDB, Trakt, …) are out of scope.
        val malIdByMappingId = HashMap<Long, Long>()
        val anilistIdByMappingId = HashMap<Long, Long>()
        for (mapping in page.included) {
            if (mapping.type != "mappings") continue
            val external = mapping.attributes.externalId?.toLongOrNull() ?: continue
            when (mapping.attributes.externalSite) {
                MAL_MAPPING_SITE -> malIdByMappingId[mapping.id] = external
                ANILIST_MAPPING_SITE -> anilistIdByMappingId[mapping.id] = external
            }
        }

        return page.data.mapNotNull { row ->
            val mangaRef = row.relationships.manga?.data ?: return@mapNotNull null
            val mangaId = mangaRef.id
            val manga = mangaById[mangaId] ?: return@mapNotNull null
            val tags = manga.relationships?.categories?.data
                ?.mapNotNull { categoryTitleById[it.id] }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            // Resolve cross-tracker ids via this manga's mappings relationship — first match wins.
            val mappingRefs = manga.relationships?.mappings?.data.orEmpty()
            val malId = mappingRefs.firstNotNullOfOrNull { malIdByMappingId[it.id] }
            val anilistId = mappingRefs.firstNotNullOfOrNull { anilistIdByMappingId[it.id] }
            KitsuLibraryEntry(
                mangaId = mangaId,
                title = manga.attributes.canonicalTitle.orEmpty(),
                status = row.attributes.status,
                ratingTwenty = row.attributes.ratingTwenty,
                tags = tags,
                malId = malId,
                anilistId = anilistId,
            )
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
            client.newCall(POST(LOGIN_URL, body = formBody))
                .awaitSuccess()
                .parseAs()
        }
    }

    suspend fun getCurrentUser(): String {
        return withIOContext {
            val url = "${BASE_URL}users".toUri().buildUpon()
                .encodedQuery("filter[self]=true")
                .build()
            authClient.newCall(GET(url.toString()))
                .awaitSuccess()
                .parseAs<KitsuCurrentUserResult>()
                .data[0]
                .id
        }
    }

    companion object {
        private const val CLIENT_ID =
            "dd031b32d2f56c990b1425efe6c42ad847e7fe3ab46bf1299f05ecd856bdb7dd"
        private const val CLIENT_SECRET =
            "54d7307928f63414defd96399fc31ba847961ceaecef3a5fd93144e960c0e151"

        private const val BASE_URL = "https://kitsu.app/api/edge/"
        private const val LOGIN_URL = "https://kitsu.app/api/oauth/token"
        private const val BASE_MANGA_URL = "https://kitsu.app/manga/"
        private const val ALGOLIA_KEY_URL = "https://kitsu.app/api/edge/algolia-keys/media/"

        private const val ALGOLIA_URL =
            "https://AWQO5J657S-dsn.algolia.net/1/indexes/production_media/query/"
        private const val ALGOLIA_APP_ID = "AWQO5J657S"
        private const val ALGOLIA_FILTER =
            "&facetFilters=%5B%22kind%3Amanga%22%5D&attributesToRetrieve=%5B%22synopsis%22%2C%22canonicalTitle%22%2C%22chapterCount%22%2C%22posterImage%22%2C%22startDate%22%2C%22subtype%22%2C%22endDate%22%2C%20%22id%22%5D"

        private const val VND_API_JSON = "application/vnd.api+json"
        private val VND_JSON_MEDIA_TYPE = VND_API_JSON.toMediaType()

        /** Kitsu's `externalSite` slug for MyAnimeList manga — used to resolve cross-tracker dedup id. */
        private const val MAL_MAPPING_SITE = "myanimelist/manga"

        /** Kitsu's `externalSite` slug for AniList manga — secondary cross-tracker dedup key. */
        private const val ANILIST_MAPPING_SITE = "anilist/manga"

        fun mangaUrl(remoteId: Long): String {
            return BASE_MANGA_URL + remoteId
        }

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
