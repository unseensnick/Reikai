package reikai.domain.recommendation.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ALRecsResponse(
    val data: ALRecsData,
)

/** Response for a single tracked media: its genres + its recommendations, in one query. */
@Serializable
data class ALMediaContextResponse(
    val data: ALMediaContextData,
)

@Serializable
data class ALMediaContextData(
    @SerialName("Media")
    val media: ALMediaContext? = null,
)

@Serializable
data class ALMediaContext(
    val genres: List<String> = emptyList(),
    val recommendations: ALRecsEdges? = null,
)

@Serializable
data class ALRecsData(
    @SerialName("Page")
    val page: ALRecsPage,
)

@Serializable
data class ALRecsPage(
    val media: List<ALRecsMedia> = emptyList(),
)

@Serializable
data class ALRecsMedia(
    val title: ALRecsTitle? = null,
    val synonyms: List<String> = emptyList(),
    val recommendations: ALRecsEdges? = null,
)

@Serializable
data class ALRecsEdges(
    val edges: List<ALRecsEdge> = emptyList(),
)

@Serializable
data class ALRecsEdge(
    val node: ALRecsNode? = null,
)

@Serializable
data class ALRecsNode(
    val mediaRecommendation: ALRecsMediaRecommendation? = null,
)

@Serializable
data class ALRecsMediaRecommendation(
    val id: Long? = null,
    val countryOfOrigin: String? = null,
    val siteUrl: String? = null,
    val title: ALRecsTitle? = null,
    val synonyms: List<String> = emptyList(),
    val coverImage: ALRecsCover? = null,
)

@Serializable
data class ALRecsTitle(
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class ALRecsCover(
    val large: String? = null,
)
