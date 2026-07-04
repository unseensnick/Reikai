package reikai.domain.recommendation.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MUSeriesResponse(
    val recommendations: List<MURec> = emptyList(),
    @SerialName("category_recommendations")
    val categoryRecommendations: List<MURec> = emptyList(),
    val genres: List<MUGenre> = emptyList(),
)

@Serializable
data class MUGenre(
    val genre: String,
)

@Serializable
data class MURec(
    @SerialName("series_name")
    val seriesName: String,
    @SerialName("series_url")
    val seriesUrl: String,
    @SerialName("series_image")
    val seriesImage: MUSeriesImage? = null,
)

@Serializable
data class MUSeriesImage(
    val url: MUImageUrl? = null,
)

@Serializable
data class MUImageUrl(
    val original: String? = null,
)

@Serializable
data class MUSearchResponse(
    val results: List<MUSearchResult> = emptyList(),
)

@Serializable
data class MUSearchResult(
    val record: MUSearchRecord,
)

@Serializable
data class MUSearchRecord(
    @SerialName("series_id")
    val seriesId: Long,
)
