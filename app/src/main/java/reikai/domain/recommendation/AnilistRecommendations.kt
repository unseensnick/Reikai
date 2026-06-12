package reikai.domain.recommendation

import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import reikai.domain.recommendation.dto.ALMediaContextResponse
import reikai.domain.recommendation.dto.ALRecsEdge
import reikai.domain.recommendation.dto.ALRecsMedia
import reikai.domain.recommendation.dto.ALRecsMediaRecommendation
import reikai.domain.recommendation.dto.ALRecsResponse
import reikai.domain.recommendation.dto.ALRecsTitle

/**
 * AniList public GraphQL recommendations (`Media.recommendations`). No auth required, so the shared
 * recs client is fine. AniList is the one provider that reports alternative titles (romaji / english
 * / native / synonyms), so its candidates carry [RelatedMangaCandidate.altTitles] for stronger dedup.
 */
class AnilistRecommendations(
    private val client: OkHttpClient,
    override val trackerId: Long,
) : TrackerRecommendations() {

    override val trackerName: String = "AniList"

    override suspend fun getRecsById(remoteId: Long): List<RelatedMangaCandidate> {
        val payload = buildJsonObject {
            put("query", QUERY_BY_ID)
            put("variables", buildJsonObject { put("id", remoteId) })
        }
        return execute(payload)
    }

    override suspend fun getRecsBySearch(title: String): List<RelatedMangaCandidate> {
        val payload = buildJsonObject {
            put("query", QUERY_BY_SEARCH)
            put("variables", buildJsonObject { put("search", title) })
        }
        // The search variant returns multiple medias whose own titles loosely match; keep only the
        // ones that actually contain the query in some title variant before pulling their recs.
        return execute(payload) { media -> media.matchesQuery(title) }
    }

    override suspend fun getMediaContext(remoteId: Long): MediaContext {
        val payload = buildJsonObject {
            put("query", QUERY_MEDIA_CONTEXT)
            put("variables", buildJsonObject { put("id", remoteId) })
        }
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val media = with(json) {
            client.newCall(POST(ENDPOINT, body = body)).awaitSuccess().parseAs<ALMediaContextResponse>()
        }.data.media ?: return MediaContext(emptyList(), emptyList())

        return MediaContext(
            genres = media.genres,
            recommendations = media.recommendations?.edges.orEmpty().toCandidates(),
        )
    }

    private suspend fun execute(
        payload: JsonObject,
        filter: (ALRecsMedia) -> Boolean = { true },
    ): List<RelatedMangaCandidate> {
        val body = payload.toString().toRequestBody(JSON_MEDIA_TYPE)
        val data = with(json) {
            client.newCall(POST(ENDPOINT, body = body)).awaitSuccess().parseAs<ALRecsResponse>()
        }

        return data.data.page.media
            .filter(filter)
            .flatMap { it.recommendations?.edges.orEmpty() }
            .toCandidates()
    }

    private fun List<ALRecsEdge>.toCandidates(): List<RelatedMangaCandidate> =
        mapNotNull { it.node?.mediaRecommendation }
            .mapNotNull { rec ->
                val url = rec.siteUrl ?: return@mapNotNull null
                candidate(
                    url = url,
                    title = rec.pickTitle(),
                    thumbnailUrl = rec.coverImage?.large,
                    remoteId = rec.id,
                    altTitles = rec.altTitles(),
                )
            }

    private fun ALRecsMedia.matchesQuery(query: String): Boolean {
        if (title?.contains(query) == true) return true
        return synonyms.any { it.contains(query, ignoreCase = true) }
    }

    private fun ALRecsTitle.contains(query: String): Boolean =
        romaji?.contains(query, ignoreCase = true) == true ||
            english?.contains(query, ignoreCase = true) == true ||
            native?.contains(query, ignoreCase = true) == true

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

    private fun ALRecsMediaRecommendation.altTitles(): List<String> =
        (listOfNotNull(title?.romaji, title?.english, title?.native) + synonyms)
            .filter { it.isNotBlank() }

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
                          id
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

        private val QUERY_MEDIA_CONTEXT = """
            query MediaContext(${'$'}id: Int!) {
              Media(id: ${'$'}id, type: MANGA) {
                genres
                recommendations {
                  edges {
                    node {
                      mediaRecommendation {
                        id
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
                          id
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
