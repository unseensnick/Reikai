package eu.kanade.tachiyomi.data.track.kitsu.dto

import kotlinx.serialization.Serializable

/**
 * RK: wire types for the Kitsu JSON:API `manga/{id}?include=staff.person,genres` endpoint, used by
 * "Fill from tracker". Kitsu's GraphQL (`findLibraryEntryById`) that upstream/Komikku use is gated
 * (403), so this goes through the same JSON:API edge REST the rest of the tracker already uses.
 * Reuses KitsuRelationshipRef / KitsuRelationshipRefList / KitsuRelationshipId from KitsuLibrary.kt.
 */
@Serializable
data class KitsuMetadataResult(
    val data: KitsuMetadataManga,
    val included: List<KitsuMetadataIncluded> = emptyList(),
)

@Serializable
data class KitsuMetadataManga(
    val id: Long,
    val attributes: KitsuMetadataAttributes = KitsuMetadataAttributes(),
    val relationships: KitsuMetadataRelationships = KitsuMetadataRelationships(),
)

@Serializable
data class KitsuMetadataIncluded(
    val id: Long,
    val type: String,
    val attributes: KitsuMetadataAttributes = KitsuMetadataAttributes(),
    val relationships: KitsuMetadataRelationships = KitsuMetadataRelationships(),
)

@Serializable
data class KitsuMetadataAttributes(
    // manga
    val canonicalTitle: String? = null,
    val synopsis: String? = null,
    val description: String? = null,
    val posterImage: KitsuPosterImage? = null,
    // included: `mediaStaff` carries role; `people` carry name; `categories` carry title + nsfw.
    val role: String? = null,
    val name: String? = null,
    val title: String? = null,
    val nsfw: Boolean? = null,
)

@Serializable
data class KitsuPosterImage(
    val original: String? = null,
    val large: String? = null,
    val medium: String? = null,
)

@Serializable
data class KitsuMetadataRelationships(
    val staff: KitsuRelationshipRefList? = null,
    // Kitsu's `genres` relationship is usually empty; `categories` holds the real genre-like list
    // (matches how the taste-profile fetcher reads Kitsu).
    val categories: KitsuRelationshipRefList? = null,
    // present on a `mediaStaff` included resource: the person it points to.
    val person: KitsuRelationshipRef? = null,
)
