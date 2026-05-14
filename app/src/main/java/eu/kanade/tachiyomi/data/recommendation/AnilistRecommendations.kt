package eu.kanade.tachiyomi.data.recommendation

import eu.kanade.tachiyomi.data.recommendation.dto.ALRecsMedia
import eu.kanade.tachiyomi.data.recommendation.dto.ALRecsMediaRecommendation
import eu.kanade.tachiyomi.data.recommendation.dto.ALRecsResponse
import eu.kanade.tachiyomi.data.recommendation.dto.ALRecsTitle
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * AniList public GraphQL recommendations. Endpoint requires no auth; the shared OkHttp client
 * is fine. Ported from Komikku's [`AniListPagingSource`][https://github.com/komikku-app/komikku].
 */
class AnilistRecommendations(
    private val client: OkHttpClient,
) : TrackerRecommendations() {
    override val trackerName: String = "AniList"

    override suspend fun getRecsById(remoteId: Long): List<SManga> {
        val payload = buildJsonObject {
            put("query", QUERY_BY_ID)
            put("variables", buildJsonObject { put("id", remoteId) })
        }
        return execute(payload)
    }

    override suspend fun getRecsBySearch(title: String): List<SManga> {
        val payload = buildJsonObject {
            put("query", QUERY_BY_SEARCH)
            put("variables", buildJsonObject { put("search", title) })
        }
        // For the search variant, AniList returns multiple medias whose own titles loosely match
        // the query — filter to the ones that actually contain the search term in any title
        // variant or synonym (mirrors Komikku's filter step).
        return execute(payload) { media -> media.matchesQuery(title) }
    }

    private suspend fun execute(
        payload: JsonObject,
        filter: (ALRecsMedia) -> Boolean = { true },
    ): List<SManga> {
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val data = client.newCall(POST(ENDPOINT, body = body)).awaitSuccess().parseAs<ALRecsResponse>()

        return data.data.page.media
            .filter(filter)
            .flatMap { it.recommendations?.edges.orEmpty() }
            .mapNotNull { it.node?.mediaRecommendation }
            .mapNotNull { rec ->
                val url = rec.siteUrl ?: return@mapNotNull null
                SManga.create().apply {
                    this.url = url
                    this.title = rec.pickTitle()
                    this.thumbnail_url = rec.coverImage?.large
                    this.initialized = true
                }
            }
    }

    private fun ALRecsMedia.matchesQuery(query: String): Boolean {
        if (title?.contains(query) == true) return true
        return synonyms.any { it.contains(query, ignoreCase = true) }
    }

    private fun ALRecsTitle.contains(query: String): Boolean {
        return romaji?.contains(query, ignoreCase = true) == true ||
            english?.contains(query, ignoreCase = true) == true ||
            native?.contains(query, ignoreCase = true) == true
    }

    private fun ALRecsMediaRecommendation.pickTitle(): String {
        val english = title?.english
        val romaji = title?.romaji
        val native = title?.native
        val synonym = synonyms.firstOrNull()
        val isJp = countryOfOrigin == "JP"

        return when {
            !english.isNullOrBlank() -> english
            isJp && !romaji.isNullOrBlank() -> romaji
            !synonym.isNullOrBlank() -> synonym
            !isJp && !romaji.isNullOrBlank() -> romaji
            else -> native ?: "NO NAME FOUND"
        }
    }

    companion object {
        private const val ENDPOINT = "https://graphql.anilist.co/"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val QUERY_BY_ID = """
            query Recommendations(${'$'}id: Int!) {
              Page {
                media(id: ${'$'}id, type: MANGA) {
                  recommendations {
                    edges {
                      node {
                        mediaRecommendation {
                          countryOfOrigin
                          siteUrl
                          title { romaji english native }
                          synonyms
                          coverImage { large }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        private val QUERY_BY_SEARCH = """
            query Recommendations(${'$'}search: String!) {
              Page {
                media(search: ${'$'}search, type: MANGA) {
                  title { romaji english native }
                  synonyms
                  recommendations {
                    edges {
                      node {
                        mediaRecommendation {
                          countryOfOrigin
                          siteUrl
                          title { romaji english native }
                          synonyms
                          coverImage { large }
                        }
                      }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
