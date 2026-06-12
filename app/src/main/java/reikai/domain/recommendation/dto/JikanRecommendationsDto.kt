package reikai.domain.recommendation.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JikanRecsResponse(
    val data: List<JikanRecsEntry> = emptyList(),
)

@Serializable
data class JikanRecsEntry(
    val entry: JikanRecsManga,
)

@Serializable
data class JikanRecsManga(
    @SerialName("mal_id")
    val malId: Long? = null,
    val title: String,
    val url: String,
    val images: JikanImages? = null,
)

@Serializable
data class JikanImages(
    val webp: JikanImageUrl? = null,
    val jpg: JikanImageUrl? = null,
)

@Serializable
data class JikanImageUrl(
    @SerialName("image_url")
    val imageUrl: String? = null,
)

@Serializable
data class JikanMangaResponse(
    val data: JikanMangaData,
)

@Serializable
data class JikanMangaData(
    val genres: List<JikanGenre> = emptyList(),
    // MAL files "Psychological" / "Isekai" etc. under themes, not genres, so include both.
    val themes: List<JikanGenre> = emptyList(),
)

@Serializable
data class JikanGenre(
    val name: String,
)

@Serializable
data class JikanSearchResponse(
    val data: List<JikanSearchEntry> = emptyList(),
)

@Serializable
data class JikanSearchEntry(
    @SerialName("mal_id")
    val malId: Long,
)
