package reikai.domain.recommendation.dto

import kotlinx.serialization.Serializable

/**
 * The shape both `/api/mangas` and `/api/mangas/{id}/similar` return; only the fields the carousel
 * needs are modeled. [url] and the [SMRecsImage] paths are relative to `shikimori.io`.
 */
@Serializable
data class SMRecsManga(
    val id: Long,
    val name: String,
    val url: String,
    val image: SMRecsImage? = null,
)

@Serializable
data class SMRecsImage(
    val original: String? = null,
    val preview: String? = null,
)

/** `/api/mangas/{id}` (single object) carries the genres the compact array shape omits. */
@Serializable
data class SMMangaDetail(
    val genres: List<SMGenre> = emptyList(),
)

@Serializable
data class SMGenre(
    val name: String,
)
