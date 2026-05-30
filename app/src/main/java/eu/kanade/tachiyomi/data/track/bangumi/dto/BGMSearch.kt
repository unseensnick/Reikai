package eu.kanade.tachiyomi.data.track.bangumi.dto

import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BGMSearchResult(
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
    val data: List<BGMSubject> = emptyList(),
)

@Serializable
// Incomplete DTO with only the attributes we need.
data class BGMSubject(
    val id: Long,
    @SerialName("name_cn")
    val nameCn: String = "",
    val name: String,
    val summary: String? = null,
    val date: String? = null, // YYYY-MM-DD
    val images: BGMSubjectImages? = null,
    val volumes: Long = 0,
    val eps: Long = 0,
    val rating: BGMSubjectRating? = null,
    val platform: String? = null,
) {
    fun toTrackSearch(trackId: Long): TrackSearch = TrackSearch.create(trackId).apply {
        media_id = this@BGMSubject.id
        title = nameCn.ifBlank { name }
        cover_url = images?.common.orEmpty()
        summary = if (nameCn.isNotBlank()) {
            "作品原名：$name" + this@BGMSubject.summary?.let { "\n${it.trim()}" }.orEmpty()
        } else {
            this@BGMSubject.summary?.trim().orEmpty()
        }
        score = rating?.score?.toFloat() ?: -1.0f
        tracking_url = "https://bangumi.tv/subject/${this@BGMSubject.id}"
        total_chapters = eps
        start_date = date ?: ""
    }
}

@Serializable
data class BGMSubjectImages(
    val common: String? = null,
)

@Serializable
data class BGMSubjectRating(
    val score: Double? = null,
)
