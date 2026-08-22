package eu.kanade.tachiyomi.data.track.kitsu.dto

import kotlinx.serialization.Serializable

/**
 * RK: wire types for the "Fill from tracker" metadata read. This has its own query rather than
 * reusing upstream's shared search fragment, which caps `staff` at five and carries no categories,
 * so the credits would truncate and the genre list would be missing entirely.
 */
@Serializable
data class KitsuMetadataResult(
    val data: KitsuMetadataData,
)

@Serializable
data class KitsuMetadataData(
    val findMangaById: KitsuMangaMetadata? = null,
)

@Serializable
data class KitsuMangaMetadata(
    val id: String,
    val titles: KitsuMangaTitles,
    val description: Map<String, String> = emptyMap(),
    val posterImage: KitsuMetadataPoster? = null,
    val staff: KitsuMangaStaffData = KitsuMangaStaffData(emptyList()),
    val categories: KitsuCategoryConnection = KitsuCategoryConnection(),
)

/**
 * Its own poster type rather than upstream's [KitsuMangaPosters], which also requires the `views`
 * list its search query asks for. Fill-from-tracker wants the full-size cover, so it selects only
 * `original` and must not be tied to a selection set it does not share.
 */
@Serializable
data class KitsuMetadataPoster(
    val original: KitsuMangaPoster,
)
